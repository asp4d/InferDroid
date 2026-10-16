#!/usr/bin/env python3
"""Generate a local one-step SDXS CPU reference and save each component boundary."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path

from private_io import prepare_output, private_path, run_private, workspace_root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--prompt", default="A small red robot reading a book in a sunny garden, digital illustration")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    root = workspace_root(__file__)
    try:
        directory = private_path(root, args.model_dir)
        output = prepare_output(root, args.output_dir)
    except ValueError as error:
        parser.error(str(error))
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["OMP_NUM_THREADS"] = str(args.threads)

    def generate():
        import numpy as np
        from PIL import Image
        import torch
        from diffusers import AutoencoderTiny, EulerDiscreteScheduler, UNet2DConditionModel
        from diffusers.models.attention_processor import AttnProcessor
        from transformers import CLIPTextModel, CLIPTokenizer

        torch.set_num_threads(args.threads)
        tokenizer = CLIPTokenizer.from_pretrained(str(directory / "tokenizer"), local_files_only=True)
        encoder = CLIPTextModel.from_pretrained(str(directory / "text_encoder"), local_files_only=True,
            use_safetensors=True, torch_dtype=torch.float32, attn_implementation="eager").eval().requires_grad_(False)
        denoiser = UNet2DConditionModel.from_pretrained(str(directory / "unet"), local_files_only=True,
            use_safetensors=True, torch_dtype=torch.float32).eval().requires_grad_(False)
        denoiser.set_attn_processor(AttnProcessor())
        vae = AutoencoderTiny.from_pretrained(str(directory / "vae"), local_files_only=True,
            use_safetensors=True, torch_dtype=torch.float32).eval().requires_grad_(False)
        scheduler = EulerDiscreteScheduler.from_pretrained(str(directory / "scheduler"), local_files_only=True)
        scheduler.set_timesteps(1, device="cpu")
        tokens = tokenizer(args.prompt, padding="max_length", max_length=77,
            truncation=True, return_tensors="pt").input_ids.to(torch.int32)
        generator = torch.Generator().manual_seed(args.seed)
        with torch.no_grad():
            embeddings = encoder(input_ids=tokens, return_dict=False)[0]
            latents = torch.randn((1, 4, 64, 64), generator=generator) * scheduler.init_noise_sigma
            step = scheduler.timesteps[0]
            scaled = scheduler.scale_model_input(latents, step)
            timestep = step.reshape(1).to(torch.float32)
            noise = denoiser(scaled, timestep, encoder_hidden_states=embeddings, return_dict=False)[0]
            denoised = scheduler.step(noise, step, latents, return_dict=False)[0]
            decoder_input = denoised / vae.config.scaling_factor
            decoded = vae.decode(decoder_input, return_dict=False)[0]
        def save(name, values, result):
            arrays = {"input_" + str(i): tensor.cpu().numpy() for i, tensor in enumerate(values)}
            arrays["output_0"] = result.cpu().numpy()
            np.savez(output / (name + "-reference.npz"), **arrays)
        save("text_encoder", (tokens,), embeddings)
        save("unet", (scaled, timestep, embeddings), noise)
        save("decoder", (decoder_input,), decoded)
        np.savez(output / "scheduler-state.npz", initial_latents=latents.cpu().numpy())
        image = np.clip((decoded[0].permute(1, 2, 0).cpu().numpy() + 1) / 2, 0, 1)
        Image.fromarray(np.rint(image * 255).astype(np.uint8)).save(output / "reference.png")
        (output / "report.json").write_text(json.dumps({
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
            "source_repo": "IDKiro/sdxs-512-dreamshaper",
            "source_revision": "76f720262bb051da75666b22c902a78c8e16c763",
            "prompt": args.prompt, "seed": args.seed, "size": [512, 512],
            "steps": 1, "guidance_scale": 0, "scheduler": "EulerDiscreteScheduler",
            "timesteps": scheduler.timesteps.cpu().tolist(), "backend": "PyTorch CPU",
            "npu_generation_verified": False,
        }, indent=2) + "\n")
        return 0

    return run_private(output, generate, min_free_bytes=2 * 1024**3)


if __name__ == "__main__":
    raise SystemExit(main())
