# Tensor G5 model compilation tools

These authored helper scripts export SDXS-512 DreamShaper to LiteRT, compile
local graphs for Tensor G5, and optionally check components on a connected
Pixel. SDK files and notebook code are not bundled. The scripts use public
LiteRT/PyTorch APIs and public model configurations.

Use Linux x86_64 and Python 3.12 with `venv`. Public dependency and model
downloads need internet access. Supply your approved Google Tensor SDK locally.
Phone tools require `adb`, one authorized Tensor G5 device and an Android NDK
with an ARM64 API-31 C++ compiler. Compilation/export runs have a host memory
guard; allow several GB of disk for environments and intermediate artifacts.

## Workflow from an InferDroid checkout

Run from the project root:

```bash
python3 research/tensor-g5/tooling/run_sdxs.py \
  --sdk "$PWD/.local/tensor-g5/sdk/google_tensor_ml_sdk" \
  --output-dir "$PWD/.local/tensor-g5/outputs/my-sdxs-run"
```

This sets up two Python environments, downloads the pinned public model,
exports and CPU-checks its three components, and compiles for Tensor G5. Add
`--run-on-pixel --ndk-dir /absolute/path/to/android-ndk` for native device
comparisons and an image generated through host orchestration. This standalone
harness uses host tokenization/scheduling; the [Android integration](../../../docs/image-generation.md)
performs the full pipeline on the phone.

The optional `--allow-numerical-differences` continues after a finite tensor
fails the chosen comparison and preserves that failure in its local report.
The compiled denoiser still needs accuracy validation. On later runs,
`--skip-setup` reuses the existing environments and SDK configuration. Use a new
output directory. `--prompt '...' --seed 7` selects another input.

`workflow.json` records the stages. Compiled models are in each
`*-g5/models/` directory. Pass the workflow output directory to the Android
build's `--compiled-run` argument.

## Compile an existing graph

```bash
bash research/tensor-g5/tooling/setup.sh \
  "$PWD/.local/tensor-g5/sdk/google_tensor_ml_sdk"
.local/tensor-g5/tooling/.venv/bin/python \
  research/tensor-g5/tooling/compile.py --smoke \
  --output-dir .local/tensor-g5/outputs/my-smoke-test
.local/tensor-g5/tooling/.venv/bin/python \
  research/tensor-g5/tooling/compile.py \
  --model .local/tensor-g5/models/model.tflite \
  --output-dir .local/tensor-g5/outputs/my-model \
  --truncation-type no_truncation
```

`--help` lists flags, including repeatable `--cpu-op`, `--large-model` and
`--sharding`. Model inputs, SDK configuration and all outputs stay inside
`.local/tensor-g5/`; existing results are preserved. The pinned exporter uses
LiteRT 2.2.0 in `tooling/.export-venv` within that workspace; AOT/runtime tools
use LiteRT 2.3.0 in `tooling/.venv`. Setup records resolved dependencies and SDK
configuration in the local workspace rather than editing the source locks.

The launcher forks a worker before loading LiteRT or SDK modules and captures
its OS stdout/stderr in `process.log`, including ordinary native-library,
subprocess and shutdown output. Terminal output contains fixed status messages;
do not automatically upload or paste the private logs or reports. Worker core
dumps are disabled and newly created files use a restrictive umask. This is log
containment, not a network sandbox or an SDK-use authorization.

## Reusable ZIP

```bash
python3 research/tensor-g5/tooling/pack_toolkit.py \
  --archive .local/tensor-g5/artifacts/tensor-g5-tools.zip
```

The scripts-only ZIP has an explicit allowlist and a `FILES.sha256` manifest.
It excludes SDKs, models, environments, configuration, research notes, results,
logs and binaries. The regular app implementation is versioned under `app/src/`
with the rest of InferDroid; see the [research README](../README.md).

A standalone tooling ZIP can be extracted into a private directory outside the
checkout. In that case the extraction directory becomes its workspace. Put
the SDK in its `sdk/` subdirectory and run
`python3 run_sdxs.py --sdk "$PWD/sdk/google_tensor_ml_sdk" --output-dir "$PWD/outputs/first-run"`.
Its environments and compiler configuration are created in `tooling/` under
that extraction directory. All dependencies and model files are supplied at use
time; no third-party binaries are included in the ZIP.
