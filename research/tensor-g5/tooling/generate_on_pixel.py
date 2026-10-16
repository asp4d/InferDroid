#!/usr/bin/env python3
"""Run SDXS model components on a Pixel, with host tokenization and scheduling."""

import argparse
from datetime import datetime, timezone
import gc
import json
import os
from pathlib import Path
import subprocess

from private_io import prepare_output, private_path, run_private, workspace_root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("model-dir", "probe", "runtime-dir", "output-dir"):
        parser.add_argument("--" + name, required=True, type=Path)
    for name, backend in (("text-encoder", "hybrid"), ("unet", "hybrid"), ("decoder", "npu")):
        parser.add_argument("--" + name + "-model", required=True, type=Path)
        parser.add_argument("--" + name + "-export", required=True, type=Path,
                            help="Directory containing the original TFLite graph and export report.")
        parser.add_argument("--" + name + "-backend", choices=("cpu", "hybrid", "npu"), default=backend)
    parser.add_argument("--runtime-python", type=Path,
                        default=workspace_root(__file__) / "tooling/.venv/bin/python")
    parser.add_argument("--prompt", default="A small red robot reading a book in a sunny garden, digital illustration")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--allow-numerical-differences", action="store_true",
                        help="Continue after a finite output fails the 1%% component check; record the failure.")
    args = parser.parse_args()
    if args.threads < 1:
        parser.error("Use at least one CPU thread.")
    root = workspace_root(__file__)
    try:
        paths = {name: private_path(root, value) for name, value in vars(args).items()
                 if isinstance(value, Path) and name not in ("output_dir", "runtime_python")}
        # Preserve the venv executable path: resolving its final symlink would
        # invoke the system Python without the venv's installed packages.
        runtime_python = args.runtime_python.expanduser().absolute()
        private_path(root, runtime_python.parent)
        if not runtime_python.is_file():
            raise ValueError("Set up the separate AOT/runtime Python environment first.")
        paths["runtime_python"] = runtime_python
        output = prepare_output(root, args.output_dir)
    except ValueError as error:
        parser.error(str(error))
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["OMP_NUM_THREADS"] = str(args.threads)

    def generate():
        import numpy as np
        from PIL import Image
        import torch
        from ai_edge_litert.interpreter import Interpreter
        from diffusers import EulerDiscreteScheduler
        from transformers import CLIPTokenizer

        torch.set_num_threads(args.threads)
        scheduler = EulerDiscreteScheduler.from_pretrained(str(paths["model_dir"] / "scheduler"), local_files_only=True)
        scheduler.set_timesteps(1, device="cpu")
        tokenizer = CLIPTokenizer.from_pretrained(str(paths["model_dir"] / "tokenizer"), local_files_only=True)
        tokens = tokenizer(args.prompt, padding="max_length", max_length=77,
                           truncation=True, return_tensors="np").input_ids.astype(np.int32)
        generator = torch.Generator().manual_seed(args.seed)
        latents = torch.randn((1, 4, 64, 64), generator=generator) * scheduler.init_noise_sigma
        step = scheduler.timesteps[0]
        scaled = scheduler.scale_model_input(latents, step).numpy()
        record = {"started_at_utc": datetime.now(timezone.utc).isoformat(),
                  "source_repo": "IDKiro/sdxs-512-dreamshaper",
                  "source_revision": "76f720262bb051da75666b22c902a78c8e16c763",
                  "prompt": args.prompt, "seed": args.seed, "size": [512, 512],
                  "steps": 1, "guidance_scale": 0, "scheduler": "EulerDiscreteScheduler",
                  "orchestration": "Host tokenization, scheduler and PNG encoding; model inference on Pixel.",
                  "component_checks": {}, "image_generated": False}
        report_path = output / "report.json"

        def save_record():
            report_path.write_text(json.dumps(record, indent=2) + "\n")

        save_record()

        def component(name, arrays):
            export = paths[name + "_export"]
            contract = json.loads((export / "report.json").read_text())
            stage = output / name
            stage.mkdir()
            interpreter = Interpreter(model_path=str(export / (name + ".tflite")), num_threads=args.threads)
            interpreter.allocate_tensors()
            for item in interpreter.get_input_details():
                matches = [entry for entry in contract["inputs"] if entry["name"] == item["name"]]
                if len(matches) != 1:
                    raise RuntimeError("The CPU reference input contract is ambiguous.")
                interpreter.set_tensor(item["index"], arrays[matches[0]["reference_index"]])
            interpreter.invoke()
            expected = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
            del interpreter
            gc.collect()
            reference = stage / "reference.npz"
            np.savez(reference, **{"input_" + str(i): array for i, array in enumerate(arrays)}, output_0=expected)
            command = [str(paths["runtime_python"]), str(Path(__file__).resolve().parent / "run_device_probe.py"),
                       "--model", str(paths[name + "_model"]), "--reference", str(reference),
                       "--contract", str(export / "report.json"), "--probe", str(paths["probe"]),
                       "--runtime-dir", str(paths["runtime_dir"]), "--output-dir", str(stage / "device")]
            backend = getattr(args, name + "_backend")
            if backend != "npu":
                command.append("--" + backend)
            execution = subprocess.run(command)
            device_report = stage / "device/report.json"
            if not device_report.is_file():
                raise RuntimeError("The Pixel stage did not produce a report.")
            result = json.loads(device_report.read_text())
            record["component_checks"][name] = {"backend_requested": backend,
                "device_status": result["device_status"], "reference_allclose": result["reference_allclose"],
                "max_absolute_error": result.get("max_absolute_error"),
                "mean_absolute_error": result.get("mean_absolute_error"),
                "rtol": result["rtol"], "atol": result["atol"], "device_report": str(device_report)}
            save_record()
            if not result["device_status"].get("run_succeeded") or not result.get("outputs_finite"):
                raise RuntimeError("Pixel invocation failed or produced non-finite output.")
            if execution.returncode != 0 and not args.allow_numerical_differences:
                raise RuntimeError("The component failed numerical validation; see the local report.")
            return np.fromfile(stage / "device/output_0.bin", dtype=expected.dtype).reshape(expected.shape)

        embedding = component("text_encoder", (tokens,))
        noise = component("unet", (scaled, step.reshape(1).numpy().astype(np.float32), embedding))
        denoised = scheduler.step(torch.from_numpy(noise), step, latents, return_dict=False)[0]
        vae_config = json.loads((paths["model_dir"] / "vae/config.json").read_text())
        decoder_input = (denoised / vae_config["scaling_factor"]).numpy()
        decoded = component("decoder", (decoder_input,))
        image = np.clip((decoded[0].transpose(1, 2, 0) + 1) / 2, 0, 1)
        Image.fromarray(np.rint(image * 255).astype(np.uint8)).save(output / "pixel.png")
        record["image_generated"] = True
        record["all_component_checks_passed"] = all(item["reference_allclose"] for item in record["component_checks"].values())
        record["denoiser_npu_requested_and_ran"] = (args.unet_backend != "cpu"
            and record["component_checks"]["unet"]["device_status"].get("run_succeeded", False))
        save_record()
        return 0

    return run_private(output, generate, min_free_bytes=2 * 1024**3)


if __name__ == "__main__":
    raise SystemExit(main())
