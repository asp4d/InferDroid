# Tensor G5 image-model research tools

Image generation is integrated into the normal InferDroid application and
standard build. Its Java/JNI sources and tests live in `app/src/`; setup and
API instructions are in [image generation](../../docs/image-generation.md).
No alternate application ID, port, generated-source mirror, or build variant
is required.

This directory retains [model export, Tensor G5 compilation, device probes,
and numerical comparison tools](tooling/README.md). They use public Android,
LiteRT, Diffusers, and Transformers APIs. Google Tensor SDK files, models,
compiled components, runtime binaries, compiler configuration, and detailed
results remain under the ignored `.local/tensor-g5/` workspace. The denoiser's
numerical validation remains open, so image quality is experimental.

Run commands from the project root. The normal Android build requires only
its prepared runtimes and tokenizer; it does not require the Tensor SDK,
Python ML environments, compiler workflow, or model weights. Model export
and compilation are separate development steps for preparing compatible
weights, rather than a separate Android application.

To archive the compiler tools, use:

```bash
python3 research/tensor-g5/tooling/pack_toolkit.py \
  --archive .local/tensor-g5/artifacts/tensor-g5-tools.zip
```

The ZIP uses an explicit source allowlist and checksums. App integration is
versioned with the rest of InferDroid. Existing local archives and research
results remain preserved in `.local/`.
