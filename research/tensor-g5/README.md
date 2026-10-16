# Tensor G5 image-generation research sources

This directory contains InferDroid's own Java/JNI image integration, tests and
Python/shell tools. It uses public Android, LiteRT, Diffusers and Transformers
APIs. It contains no Google Tensor SDK files, notebook code, model weights,
compiled models, runtime binaries or device reports.

- [Android integration and build instructions](app-overlay/README.md)
- [Model export, compilation and standalone device tools](tooling/README.md)

The Android integration is an opt-in build of **InferDroid G5 Research**
(`dev.inferdroid.imagesresearch`, default API port **8081**). Its generated
sources, libraries, model package, CMake state and APKs go under
`.local/tensor-g5/android/`. SDKs, Python environments, downloads, compiler
configuration, logs and research evidence stay under `.local/tensor-g5/`.

Run commands from the InferDroid project root. Supply the SDK and model files
locally; they are not downloaded by the Android build. The compiler workflow
can prepare the public model downloads separately. Numerical validation of
the compiled denoiser remains open, so this is an experimental image pipeline.

To create a reusable source ZIP with both sets of tools and their READMEs:

```bash
python3 research/tensor-g5/app-overlay/pack.py \
  --archive .local/tensor-g5/artifacts/inferdroid-g5-sources.zip
```

The ZIP uses an explicit source allowlist and includes checksums. Extract it
into the root of a matching InferDroid checkout. Every output path must remain
inside the local workspace. Detailed reports and previous research archives
remain local; they are not copied into this source tree.
