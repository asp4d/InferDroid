#!/usr/bin/env python3
"""Export one pinned SDXS component and check it against its PyTorch reference."""

import argparse
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
import os
from pathlib import Path

from private_io import prepare_output, private_path, run_private, workspace_root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--component", choices=("decoder", "text_encoder", "unet"), required=True)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    if args.size <= 0 or args.size % 64 or args.threads < 1:
        parser.error("Use a positive size divisible by 64 and at least one CPU thread.")
    root = workspace_root(__file__)
    try:
        model_directory = private_path(root, args.model_dir)
        output = prepare_output(root, args.output_dir)
    except ValueError as error:
        parser.error(str(error))
    if not model_directory.is_dir():
        parser.error("Download the pinned model into --model-dir first.")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["OMP_NUM_THREADS"] = str(args.threads)
    os.environ["XLA_PYTHON_CLIENT_PREALLOCATE"] = "false"
    os.environ["JAX_PLATFORMS"] = "cpu"

    def export():
        import numpy as np
        import torch
        import litert_torch
        from ai_edge_litert.interpreter import Interpreter

        torch.set_num_threads(args.threads)
        if args.component == "decoder":
            from diffusers import AutoencoderTiny
            original = AutoencoderTiny.from_pretrained(
                str(model_directory), subfolder="vae", local_files_only=True,
                use_safetensors=True, torch_dtype=torch.float32,
            )

            class Wrapper(torch.nn.Module):
                def __init__(self):
                    super().__init__()
                    self.vae = original

                def forward(self, latents):
                    return self.vae.decode(latents, return_dict=False)[0]

            def inputs(seed):
                generator = torch.Generator().manual_seed(seed)
                return (torch.randn(1, 4, args.size // 8, args.size // 8, generator=generator),)
        elif args.component == "text_encoder":
            from transformers import CLIPTextModel
            original = CLIPTextModel.from_pretrained(
                str(model_directory), subfolder="text_encoder", local_files_only=True,
                use_safetensors=True, torch_dtype=torch.float32, attn_implementation="eager",
            )

            class Wrapper(torch.nn.Module):
                def __init__(self):
                    super().__init__()
                    self.encoder = original

                def forward(self, tokens):
                    return self.encoder(input_ids=tokens, return_dict=False)[0]

            def inputs(seed):
                generator = torch.Generator().manual_seed(seed)
                return (torch.randint(0, original.config.vocab_size, (1, 77), generator=generator, dtype=torch.int32),)
        else:
            from diffusers import UNet2DConditionModel
            from diffusers.models.attention_processor import AttnProcessor
            original = UNet2DConditionModel.from_pretrained(
                str(model_directory), subfolder="unet", local_files_only=True,
                use_safetensors=True, torch_dtype=torch.float32,
            )
            original.set_attn_processor(AttnProcessor())

            class Wrapper(torch.nn.Module):
                def __init__(self):
                    super().__init__()
                    self.unet = original

                def forward(self, latents, timestep, embeddings):
                    return self.unet(latents, timestep, encoder_hidden_states=embeddings, return_dict=False)[0]

            def inputs(seed):
                generator = torch.Generator().manual_seed(seed)
                return (
                    torch.randn(1, 4, args.size // 8, args.size // 8, generator=generator),
                    torch.tensor([999.0], dtype=torch.float32),
                    torch.randn(1, 77, original.config.cross_attention_dim, generator=generator),
                )

        wrapper = Wrapper().eval().requires_grad_(False)
        samples = inputs(7)
        graph = output / (args.component + ".tflite")
        with torch.no_grad():
            references = [wrapper(*batch).detach().cpu().numpy() for batch in (samples, inputs(8))]
            converted = litert_torch.convert(wrapper, samples)
            converted.export(str(graph))
        interpreter = Interpreter(model_path=str(graph), num_threads=args.threads)
        interpreter.allocate_tensors()
        details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
        if len(details) != len(samples) or len(output_details) != 1:
            raise RuntimeError("Unexpected converted model input/output contract.")
        validations = []
        for index, (batch, reference) in enumerate(zip((samples, inputs(8)), references)):
            arrays = [tensor.detach().cpu().numpy() for tensor in batch]
            unused = list(range(len(arrays)))
            mapping = []
            for detail in details:
                matching = [i for i in unused if tuple(arrays[i].shape) == tuple(detail["shape"]) and arrays[i].dtype == detail["dtype"]]
                if len(matching) != 1:
                    raise RuntimeError("Cannot uniquely map exported input by shape and dtype.")
                source = matching[0]
                unused.remove(source)
                mapping.append(source)
                interpreter.set_tensor(detail["index"], arrays[source])
            interpreter.invoke()
            actual = interpreter.get_tensor(output_details[0]["index"])
            np.testing.assert_allclose(actual, reference, rtol=1e-3, atol=1e-3)
            if not np.all(np.isfinite(actual)):
                raise RuntimeError("The converted graph produced a non-finite output.")
            saved = {"input_" + str(i): array for i, array in enumerate(arrays)}
            saved["output_0"] = reference
            np.savez(output / ("reference.npz" if index == 0 else "reference-holdout.npz"), **saved)
            validations.append({"seed": 7 + index, "passed": True, "max_absolute_error": float(np.max(np.abs(actual-reference)))})
        with graph.open("rb") as stream:
            graph_hash = hashlib.file_digest(stream, "sha256").hexdigest()
        (output / "report.json").write_text(json.dumps({
            "exported_at_utc": datetime.now(timezone.utc).isoformat(),
            "component": args.component, "image_size": args.size,
            "source_repo": "IDKiro/sdxs-512-dreamshaper",
            "source_revision": "76f720262bb051da75666b22c902a78c8e16c763",
            "packages": {name: metadata.version(name) for name in ("torch", "torchao", "litert-torch", "ai-edge-litert", "diffusers", "transformers")},
            "model_sha256": graph_hash, "model_bytes": graph.stat().st_size,
            "inputs": [{"name": item["name"], "shape": item["shape"].tolist(), "dtype": str(np.dtype(item["dtype"])), "reference_index": source} for item, source in zip(details, mapping)],
            "output": {"name": output_details[0]["name"], "shape": output_details[0]["shape"].tolist(), "dtype": str(np.dtype(output_details[0]["dtype"]))},
            "cpu_validation": validations, "npu_execution_verified": False,
        }, indent=2) + "\n")
        return 0

    return run_private(output, export, min_free_bytes=2 * 1024**3)


if __name__ == "__main__":
    raise SystemExit(main())
