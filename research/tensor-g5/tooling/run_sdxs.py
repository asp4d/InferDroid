#!/usr/bin/env python3
"""Reproduce the private SDXS export, Tensor G5 compilation and optional Pixel test."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess

from private_io import prepare_output, private_path, run_private, workspace_root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, help="Local SDK directory or compiler archive.")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--skip-setup", action="store_true", help="Reuse both existing Python environments and SDK configuration.")
    parser.add_argument("--run-on-pixel", action="store_true")
    parser.add_argument("--ndk-dir", type=Path)
    parser.add_argument("--allow-numerical-differences", action="store_true")
    parser.add_argument("--prompt", default="A small red robot reading a book in a sunny garden, digital illustration")
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()
    if not args.skip_setup and args.sdk is None:
        parser.error("Provide --sdk, or --skip-setup after preparing both environments.")
    if args.run_on_pixel and args.ndk_dir is None:
        parser.error("--run-on-pixel requires --ndk-dir and one authorized Pixel 10.")
    root = workspace_root(__file__)
    tool = Path(__file__).resolve().parent
    try:
        sdk = private_path(root, args.sdk) if args.sdk else None
        output = prepare_output(root, args.output_dir)
    except ValueError as error:
        parser.error(str(error))

    def experiment():
        record = {"started_at_utc": datetime.now(timezone.utc).isoformat(),
                  "target": "Tensor_G5", "steps": [], "completed": False}
        report = output / "workflow.json"

        def step(name, command):
            item = {"name": name, "status": "running"}
            record["steps"].append(item)
            report.write_text(json.dumps(record, indent=2) + "\n")
            result = subprocess.run([str(part) for part in command])
            item.update(status="passed" if result.returncode == 0 else "failed", exit_code=result.returncode)
            report.write_text(json.dumps(record, indent=2) + "\n")
            if result.returncode:
                raise RuntimeError("A workflow step failed; inspect the private step report.")

        if not args.skip_setup:
            step("setup_aot", ["bash", tool / "setup.sh", sdk])
            step("setup_export", ["bash", tool / "setup-export.sh"])
        aot = root / "tooling/.venv/bin/python"
        exporter = root / "tooling/.export-venv/bin/python"
        model = root / "models/sdxs-512-dreamshaper"
        step("download", [exporter, tool / "download_sdxs.py", "--model-dir", model,
                          "--component", "all", "--output-dir", output / "download"])
        exports, compiled = {}, {}
        for component in ("decoder", "text_encoder", "unet"):
            exports[component] = output / (component + "-export")
            step("export_" + component, [exporter, tool / "export_sdxs.py", "--model-dir", model,
                 "--component", component, "--output-dir", exports[component]])
            compiled[component] = output / (component + "-g5")
            command = [aot, tool / "compile.py", "--model", exports[component] / (component + ".tflite"),
                       "--output-dir", compiled[component], "--truncation-type",
                       "half" if component == "unet" else "no_truncation"]
            if component == "text_encoder":
                for operation in ("EMBEDDING_LOOKUP", "SOFTMAX"):
                    command.extend(["--cpu-op", operation])
            elif component == "unet":
                command.extend(["--large-model", "--sharding", "maximum"])
                for operation in ("SIN", "COS", "GATHER_ND", "SOFTMAX", "GELU"):
                    command.extend(["--cpu-op", operation])
            step("compile_" + component, command)
        if args.run_on_pixel:
            runtime = output / "device-runtime"
            probe = output / "device-probe"
            step("prepare_runtime", [aot, tool / "prepare_device_runtime.py", "--output-dir", runtime])
            step("build_probe", [aot, tool / "build_device_probe.py", "--runtime-dir", runtime,
                                 "--ndk-dir", args.ndk_dir, "--output-dir", probe])
            step("cpu_reference", [exporter, tool / "generate_sdxs_reference.py", "--model-dir", model,
                 "--output-dir", output / "cpu-reference", "--prompt", args.prompt, "--seed", args.seed])
            command = [exporter, tool / "generate_on_pixel.py", "--model-dir", model,
                       "--probe", probe / "npu_probe", "--runtime-dir", runtime,
                       "--output-dir", output / "pixel-image", "--prompt", args.prompt, "--seed", args.seed]
            for component in ("text_encoder", "unet", "decoder"):
                option = component.replace("_", "-")
                command.extend(["--" + option + "-model", compiled[component] / "models" / (component + "_Google_Tensor_G5.tflite"),
                                "--" + option + "-export", exports[component]])
            if args.allow_numerical_differences:
                command.append("--allow-numerical-differences")
            step("pixel_image", command)
        record["completed"] = True
        record["pixel_test_requested"] = args.run_on_pixel
        report.write_text(json.dumps(record, indent=2) + "\n")
        return 0

    return run_private(output, experiment)


if __name__ == "__main__":
    raise SystemExit(main())
