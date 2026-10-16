#!/usr/bin/env python3
"""Compile local TFLite graphs for Tensor G5, retaining SDK output privately."""

import argparse
import contextlib
import dataclasses
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
import mmap
import os
from pathlib import Path
import platform
import re
import resource
import sys
import tempfile
import traceback

from private_io import wait_worker, workspace_root


def package_versions():
    versions = {}
    for name in (
        "ai-edge-litert", "ai-edge-litert-nightly",
        "ai-edge-litert-sdk-google-tensor",
        "ai-edge-litert-sdk-google-tensor-nightly",
    ):
        try:
            versions[name] = metadata.version(name)
        except metadata.PackageNotFoundError:
            pass
    return versions


def make_smoke_model(path):
    """Create and verify a static, one-op MUL graph without model downloads."""
    import flatbuffers
    import numpy as np
    from ai_edge_litert import schema_py_generated as schema
    from ai_edge_litert.interpreter import Interpreter

    model = schema.ModelT()
    model.version = 3
    model.description = "InferDroid Tensor G5 compiler smoke test"
    model.buffers = [schema.BufferT()]
    opcode = schema.OperatorCodeT()
    opcode.builtinCode = schema.BuiltinOperator.MUL
    opcode.deprecatedBuiltinCode = schema.BuiltinOperator.MUL
    opcode.version = 1
    model.operatorCodes = [opcode]

    graph = schema.SubGraphT()
    graph.name = "main"
    graph.tensors = []
    for name in ("lhs", "rhs", "product"):
        tensor = schema.TensorT()
        tensor.name = name
        tensor.shape = [1, 4]
        tensor.type = schema.TensorType.FLOAT32
        tensor.buffer = 0
        graph.tensors.append(tensor)
    graph.inputs = [0, 1]
    graph.outputs = [2]
    op = schema.OperatorT()
    op.opcodeIndex = 0
    op.inputs = [0, 1]
    op.outputs = [2]
    op.builtinOptionsType = schema.BuiltinOptions.MulOptions
    op.builtinOptions = schema.MulOptionsT()
    graph.operators = [op]
    model.subgraphs = [graph]
    builder = flatbuffers.Builder(1024)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    path.write_bytes(bytes(builder.Output()))

    interpreter = Interpreter(model_path=str(path))
    interpreter.allocate_tensors()
    for value, tensor in zip((2.0, 3.0), interpreter.get_input_details()):
        interpreter.set_tensor(
            tensor["index"], np.full(tensor["shape"], value, dtype=np.float32)
        )
    interpreter.invoke()
    output = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
    if not np.array_equal(output, np.full((1, 4), 6.0, dtype=np.float32)):
        raise RuntimeError("The synthetic graph failed its CPU correctness check.")
    return {"passed": True, "operation": "MUL", "shape": [1, 4], "output": 6.0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--smoke", action="store_true", help="Compile a synthetic MUL graph.")
    source.add_argument("--model", type=Path, help="An already exported, static .tflite graph.")
    parser.add_argument("--output-dir", type=Path, required=True, help="A new or empty directory.")
    parser.add_argument(
        "--truncation-type", choices=("auto", "half", "bfloat16", "no_truncation")
    )
    parser.add_argument("--sharding", choices=("minimal", "moderate", "extensive", "maximum"))
    parser.add_argument("--cpu-op", action="append", default=[],
                        help="Keep this standard TFLite operator on CPU; may be repeated.")
    parser.add_argument("--large-model", action="store_true",
                        help="Enable the SDK's documented large-model support.")
    args = parser.parse_args()
    if args.smoke and args.cpu_op:
        parser.error("--cpu-op requires --model rather than --smoke.")
    if sys.version_info < (3, 11):
        parser.error("Python 3.11+ is required.")
    if sys.platform != "linux" or platform.machine() != "x86_64":
        parser.error("The Tensor SDK AOT compiler requires Linux x86_64.")
    private_root = workspace_root(__file__)
    model = args.model.expanduser().resolve() if args.model else None
    if model:
        if not model.is_relative_to(private_root):
            parser.error(f"Model inputs must be inside the private workspace: {private_root}")
        if not model.is_file() or model.suffix != ".tflite":
            parser.error("--model must be an existing .tflite file.")
        with model.open("rb") as stream:
            if stream.read(8)[4:8] != b"TFL3":
                parser.error("The input has no TFLite file identifier.")
    output = args.output_dir.expanduser().resolve()
    if not output.is_relative_to(private_root):
        parser.error(f"Results must stay inside the private workspace: {private_root}")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        parser.error("--output-dir must be new or empty; existing results are preserved.")
    os.umask(0o077)
    output.mkdir(parents=True, exist_ok=True)
    temporary_dir = output / "tmp"
    temporary_dir.mkdir()
    os.environ["TMPDIR"] = str(temporary_dir)
    tempfile.tempdir = str(temporary_dir)

    # Stable LiteRT 2.3.0 gates the Google backend behind this documented flag.
    os.environ["GOOGLE_TENSOR_BACKEND_ENABLED"] = "1"
    source_record = private_root / "tooling/compiler-source.json"
    if "GOOGLE_TENSOR_COMPILER_LIB" not in os.environ and source_record.is_file():
        configured_sdk = json.loads(source_record.read_text()).get("sdk_library_directory")
        if configured_sdk:
            os.environ["GOOGLE_TENSOR_COMPILER_LIB"] = configured_sdk
    if os.environ.get("GOOGLE_TENSOR_COMPILER_LIB"):
        sdk_directory = Path(os.environ["GOOGLE_TENSOR_COMPILER_LIB"]).expanduser().resolve()
        if not sdk_directory.is_relative_to(private_root):
            parser.error("The configured SDK must stay inside the private workspace.")
        if not (sdk_directory / "liblitert_plugin_compiler.so").is_file():
            parser.error("The configured SDK has no vendor compiler shared library.")
        os.environ["GOOGLE_TENSOR_COMPILER_LIB"] = str(sdk_directory)
    options = {}
    if args.truncation_type:
        options["google_tensor_truncation_type"] = args.truncation_type
    if args.sharding:
        options["google_tensor_sharding_intensity"] = args.sharding
    if args.large_model:
        options["google_tensor_enable_large_model_support"] = True
    record = {
        "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "target": "Tensor_G5", "packages": package_versions(),
        "private_workspace": str(private_root),
        "python": platform.python_version(), "platform": platform.platform(),
        "kind": "synthetic compiler smoke test" if args.smoke else "model graph compilation",
        "options": options, "runtime_verified": False, "status": "not_started",
    }

    # Fork before importing any LiteRT/SDK modules. Redirect the worker's actual
    # file descriptors for its entire lifetime, including native shutdown hooks
    # and subprocesses. Python stream redirection alone misses C/C++ output.
    sys.stdout.flush()
    sys.stderr.flush()
    with (output / "process.log").open("xb") as process_log:
        worker_pid = os.fork()
        if worker_pid == 0:
            os.setsid()
            os.dup2(process_log.fileno(), 1)
            os.dup2(process_log.fileno(), 2)
            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
            return run_compilation(args, model, output, record)
        worker_status = wait_worker(worker_pid, output, 2 * 1024**3)

    worker_code = os.waitstatus_to_exitcode(worker_status)
    if not (output / "report.json").exists():
        record.update(status="worker_terminated", worker_exit_code=worker_code,
                      host_memory_guard_triggered=(output / "host-memory-guard.txt").exists())
        (output / "report.json").write_text(json.dumps(record, indent=2) + "\n")
    if worker_code == 0:
        print("Compilation finished; inspect the private report locally.")
        print("Pixel execution and image quality remain unverified.")
        return 0
    print("Compilation did not finish successfully; inspect the private logs locally.")
    return 2 if worker_code == 2 else 1


def run_compilation(args, model, output, record):
    """Run only in the worker whose native stdout/stderr already point to disk."""
    options = record["options"]
    exit_code = 1
    with (output / "python.log").open("w") as log:
        with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
            try:
                if "ai-edge-litert" in record["packages"] and "ai-edge-litert-nightly" in record["packages"]:
                    raise RuntimeError("Stable and nightly LiteRT share a module; use separate environments.")
                from ai_edge_litert.aot import aot_compile as aot_lib
                from ai_edge_litert.aot.vendors.google_tensor import target as tensor_target

                if args.cpu_op:
                    from ai_edge_litert import schema_py_generated as schema
                    requested={name.upper() for name in args.cpu_op}
                    codes={getattr(schema.BuiltinOperator,name) for name in requested
                           if hasattr(schema.BuiltinOperator,name)}
                    if len(codes)!=len(requested):
                        raise ValueError("--cpu-op contains an unknown standard TFLite operator.")
                    expressions=set()
                    with model.open("rb") as source, mmap.mmap(source.fileno(),0,access=mmap.ACCESS_READ) as content:
                        graph_model=schema.Model.GetRootAsModel(content,0)
                        for graph_index in range(graph_model.SubgraphsLength()):
                            graph=graph_model.Subgraphs(graph_index)
                            for index in range(graph.OperatorsLength()):
                                operator=graph.Operators(index)
                                if graph_model.OperatorCodes(operator.OpcodeIndex()).BuiltinCode() not in codes:
                                    continue
                                for result_index in operator.OutputsAsNumpy().tolist():
                                    name=graph.Tensors(result_index).Name()
                                    if not name:
                                        raise ValueError("A CPU-filtered operator has an unnamed output.")
                                    expressions.add("^"+re.escape(name.decode())+"$")
                    if not expressions:
                        raise ValueError("None of the requested CPU operators occurs in this model.")
                    filters=output/"cpu-ops.textproto"
                    filters.write_text("filter_behavior: MATCHES_NOT_RUN_ON_TPU\n"+"".join(
                        "filters { op_name_pattern: "+json.dumps(value)+" }\n"
                        for value in sorted(expressions)))
                    options["google_tensor_op_filters_proto"]=str(filters)
                    record["requested_cpu_ops"]=sorted(requested)

                try:
                    import ai_edge_litert_sdk_google_tensor as sdk
                    sdk_path = sdk.path_to_sdk_libs()
                    compiler = Path(sdk_path) / "liblitert_plugin_compiler.so" if sdk_path else None
                    if compiler and compiler.is_file():
                        with compiler.open("rb") as stream:
                            record["vendor_compiler_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
                except ImportError:
                    if sdk_dir := os.environ.get("GOOGLE_TENSOR_COMPILER_LIB"):
                        compiler = Path(sdk_dir) / "liblitert_plugin_compiler.so"
                        with compiler.open("rb") as stream:
                            record["vendor_compiler_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
                if args.smoke:
                    model = output / "smoke.tflite"
                    record["cpu_smoke_check"] = make_smoke_model(model)
                with model.open("rb") as stream:
                    digest = hashlib.file_digest(stream, "sha256").hexdigest()
                record["input"] = {
                    "name": model.name, "size_bytes": model.stat().st_size, "sha256": digest,
                }
                target = tensor_target.Target(tensor_target.SocModel.TENSOR_G5)
                result = aot_lib.aot_compile(
                    str(model), output_dir=str(output / "intermediates"),
                    target=[target], keep_going=False, **options,
                )
                record["compilation_report"] = result.compilation_report()
                if result.failed_backends or len(result.models) != 1:
                    raise RuntimeError("Expected exactly one successful Tensor G5 result.")
                stats = result.models[0].partition_stats
                if stats is None:
                    raise RuntimeError("No NPU partition statistics; cannot validate the AOT result.")
                record["partition_stats"] = dataclasses.asdict(stats)
                offloaded = sum(s.num_ops_offloaded for s in stats.subgraph_stats)
                total = sum(s.num_total_ops for s in stats.subgraph_stats)
                if offloaded == 0:
                    raise RuntimeError("No operations compiled for the NPU.")
                result.export(str(output / "models"), model_name=model.stem)
                record.update({
                    "status": "aot_compiled_device_unverified",
                    "fully_offloaded": offloaded == total,
                    "npu_ops": offloaded, "total_ops": total,
                })
                exit_code = 0
            except Exception as error:
                record.update({
                    "status": "compilation_failed", "exception_type": type(error).__name__,
                    "error": str(error),
                })
                match = re.search(r"See (/.+?\.error)", str(error))
                if match:
                    try:
                        with Path(match.group(1)).open() as stream:
                            details = stream.read(1024 * 1024)
                        (output / "native.log").write_text(details)
                        record["native_error_lines"] = [
                            line for line in details.splitlines() if "ERROR" in line
                        ]
                        if "liblitert_plugin_compiler.so" in details and "No such file" in details:
                            record["status"] = "blocked_missing_vendor_compiler"
                            exit_code = 2
                    except OSError as log_error:
                        record["native_log_error"] = str(log_error)
                traceback.print_exc()
    (output / "report.json").write_text(json.dumps(record, indent=2) + "\n")
    print(f"{record['status']}: {output / 'report.json'}")
    if exit_code == 0:
        print("AOT compilation does not verify Pixel execution or image quality.")
    else:
        print("See python.log and, when available, native.log in the output directory.")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
