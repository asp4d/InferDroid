#!/usr/bin/env python3
"""Run one static graph on the connected Pixel and compare its saved output."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import mmap
from pathlib import Path
import shlex
import subprocess
import uuid

from private_io import prepare_output, private_path, run_private, workspace_root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("model", "reference", "contract", "probe", "runtime-dir", "output-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--cpu", action="store_true")
    parser.add_argument("--hybrid", action="store_true", help="Enable CPU and NPU for a partially compiled graph.")
    parser.add_argument("--keep-on-device", action="store_true")
    parser.add_argument("--inference-only", action="store_true",
                        help="Use the NPZ inputs without claiming agreement with a reference output.")
    parser.add_argument("--rtol", type=float, default=1e-2)
    parser.add_argument("--atol", type=float, default=1e-2)
    args = parser.parse_args()
    if args.cpu and args.hybrid:
        parser.error("Select either --cpu or --hybrid.")
    if not (0 <= args.rtol < float("inf") and 0 <= args.atol < float("inf")):
        parser.error("Tolerances must be finite and nonnegative.")
    root = workspace_root(__file__)
    try:
        paths = {name: private_path(root, getattr(args, name)) for name in
                 ("model", "reference", "contract", "probe", "runtime_dir")}
        output = prepare_output(root, args.output_dir)
    except ValueError as error:
        parser.error(str(error))

    def run():
        import numpy as np
        from ai_edge_litert import schema_py_generated as schema
        devices = subprocess.run(["adb", "devices"], check=True, capture_output=True, text=True)
        serials = [line.split()[0] for line in devices.stdout.splitlines()[1:]
                   if len(line.split()) >= 2 and line.split()[1] == "device"]
        if len(serials) != 1:
            raise RuntimeError("Connect exactly one authorized Pixel for this probe.")
        adb = ["adb", "-s", serials[0]]
        directory = "/data/local/tmp/inferdroid-g5-" + uuid.uuid4().hex
        contract = json.loads(paths["contract"].read_text())
        reference = np.load(paths["reference"], allow_pickle=False)
        staging = output / "io"
        staging.mkdir()
        with paths["model"].open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as content:
            model = schema.Model.GetRootAsModel(content, 0)
            if model.SignatureDefsLength():
                signature = model.SignatureDefs(0)
                graph = model.Subgraphs(signature.SubgraphIndex())
                indices = [signature.Inputs(i).TensorIndex() for i in range(signature.InputsLength())]
            else:
                graph = model.Subgraphs(0)
                indices = [graph.Inputs(i) for i in range(graph.InputsLength())]
            ordered_inputs = []
            for tensor_index in indices:
                tensor = graph.Tensors(tensor_index)
                name = tensor.Name().decode()
                shape = tensor.ShapeAsNumpy().tolist()
                candidates = [item for item in contract["inputs"] if item["name"] == name]
                if not candidates:
                    candidates = [item for item in contract["inputs"] if item["shape"] == shape]
                if len(candidates) != 1:
                    raise RuntimeError("The device input cannot be mapped uniquely to the reference.")
                source = candidates[0]["reference_index"]
                array = reference["input_" + str(source)]
                if list(array.shape) != shape or array.dtype != np.dtype(candidates[0]["dtype"]):
                    raise RuntimeError("Device input shape or dtype does not match the saved contract.")
                array.tofile(staging / ("input_" + str(len(ordered_inputs)) + ".bin"))
                ordered_inputs.append({"name": name, "reference_index": source})
        subprocess.run(adb + ["shell", "mkdir", "-m", "700", directory], check=True)
        for library in ("libLiteRt.so", "libLiteRtDispatch_GoogleTensor.so"):
            subprocess.run(adb + ["push", str(paths["runtime_dir"] / "arm64-v8a" / library), directory + "/" + library], check=True)
        subprocess.run(adb + ["push", str(paths["probe"]), directory + "/npu_probe"], check=True)
        subprocess.run(adb + ["push", str(paths["model"]), directory + "/model.tflite"], check=True)
        for path in sorted(staging.iterdir()):
            subprocess.run(adb + ["push", str(path), directory + "/" + path.name], check=True)
        subprocess.run(adb + ["shell", "chmod", "700", directory + "/npu_probe"], check=True)
        command = [directory + "/npu_probe", directory + "/model.tflite", directory, directory]
        if args.cpu:
            command.append("cpu")
        elif args.hybrid:
            command.append("hybrid")
        shell = "LD_LIBRARY_PATH=" + shlex.quote(directory) + " " + shlex.join(command)
        shell += " >" + shlex.quote(directory + "/runtime.log") + " 2>&1"
        execution = subprocess.run(adb + ["shell", shell], timeout=300)
        subprocess.run(adb + ["pull", directory + "/runtime.log", str(output / "device-runtime.log")], check=True)
        status_file = output / "device-status.json"
        status_copy = subprocess.run(adb + ["pull", directory + "/status.json", str(status_file)])
        status = json.loads(status_file.read_text()) if status_copy.returncode == 0 else {"run_succeeded": False, "step": "loader_or_process_failure"}
        record = {"checked_at_utc": datetime.now(timezone.utc).isoformat(),
                  "device_directory": directory, "device_status": status,
                  "device_exit_code": execution.returncode, "ordered_inputs": ordered_inputs,
                  "comparison_performed": False, "reference_allclose": None,
                  "outputs_finite": False, "rtol": args.rtol, "atol": args.atol}
        record["device"]={}
        for key,property_name in (("model","ro.product.model"),("soc","ro.soc.model"),
                ("android_api","ro.build.version.sdk"),("build_id","ro.build.id"),
                ("vendor_security_patch","ro.vendor.build.security_patch")):
            record["device"][key]=subprocess.run(adb+["shell","getprop",property_name],
                check=True,capture_output=True,text=True).stdout.strip()
        if execution.returncode == 0 and status.get("run_succeeded"):
            subprocess.run(adb + ["pull", directory + "/output_0.bin", str(output / "output_0.bin")], check=True)
            actual = np.fromfile(output / "output_0.bin", dtype=contract["output"]["dtype"]).reshape(contract["output"]["shape"])
            record["outputs_finite"] = bool(np.all(np.isfinite(actual)))
            if not args.inference_only:
                expected = reference["output_0"]
                if actual.shape != expected.shape or actual.dtype != expected.dtype:
                    raise RuntimeError("The reference output does not match the exported contract.")
                record["comparison_performed"] = True
                record["reference_allclose"] = bool(record["outputs_finite"] and np.allclose(actual, expected, rtol=args.rtol, atol=args.atol))
                record["max_absolute_error"] = float(np.max(np.abs(actual-expected)))
                record["mean_absolute_error"] = float(np.mean(np.abs(actual-expected)))
        with paths["model"].open("rb") as stream:
            record["model_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
        if not args.keep_on_device:
            cleanup=subprocess.run(adb+["shell","rm","-rf",directory])
            record["temporary_device_files_removed"]=cleanup.returncode==0
        (output / "report.json").write_text(json.dumps(record, indent=2) + "\n")
        passed = (record["device_status"].get("run_succeeded") and record["outputs_finite"]
                  and (args.inference_only or record["reference_allclose"]))
        return 0 if passed else 1

    return run_private(output, run)


if __name__ == "__main__":
    raise SystemExit(main())
