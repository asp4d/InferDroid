# InferDroid G5 Research image integration

These are authored Java/JNI sources and build tools for an optional InferDroid
variant. The image model is `sdxs-512-dreamshaper-tensor-g5`: one 512×512 PNG
per request, using one Euler step. Tokenization, noise generation, scheduling,
model inference and PNG encoding run on the phone. Image editing is not
implemented. The denoiser's numerical validation is still open.

The build uses application ID `dev.inferdroid.imagesresearch`, the label
**InferDroid G5 Research**, and default API port **8081**. Open its main screen's
**Generate images** action to enter a prompt and seed, import a model folder,
generate an image, or save the PNG.

## Build and install

Use a working InferDroid checkout with its regular native runtimes prepared.
The [compiler tools](../tooling/README.md) prepare separate local AOT and export
Python environments. Keep SDKs, downloads and compiled artifacts under
`.local/tensor-g5/`.

From the project root, using the existing validated local component directories:

```bash
python3 research/tensor-g5/app-overlay/build.py \
  --output-dir .local/tensor-g5/outputs/my-app-build
python3 research/tensor-g5/app-overlay/install.py \
  --output-dir .local/tensor-g5/outputs/my-app-install
python3 research/tensor-g5/app-overlay/verify.py \
  --output-dir .local/tensor-g5/outputs/my-app-tests
```

For a fresh compiler workflow result, supply it to the build instead:

```bash
python3 research/tensor-g5/app-overlay/build.py \
  --compiled-run .local/tensor-g5/outputs/my-sdxs-run \
  --output-dir .local/tensor-g5/outputs/my-app-build
```

Each output directory must be new. Generated files live under
`.local/tensor-g5/android/`: `generated/`, `model-package/`, `build/` and `cxx/`.
The APK is `build/app/outputs/apk/debug/app-debug.apk` under that directory.
Installation requires exactly one authorized Tensor G5 phone and streams the
three model files into the debug app's internal storage. No models are embedded
in the APK. Seeds reproduce this Android implementation's Java Gaussian noise;
the same numeric seed in PyTorch selects different initial tensors.

`prepare.py` creates a generated mirror of the base Java sources and applies
checked changes to add the screen and HTTP endpoint. A changed patch anchor
stops preparation for review. The init script `enable.gradle` selects that
mirror only for this build. The existing app's shared work gate, authentication,
loopback binding and cancellation behavior apply to image requests.

## Local API

Start the local API from the research app and use its displayed key:

```bash
curl http://127.0.0.1:8081/v1/images/generations \
  -H "Authorization: Bearer $INFERDROID_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"sdxs-512-dreamshaper-tensor-g5","prompt":"A red robot reading in a garden","seed":0,"n":1,"size":"512x512","response_format":"b64_json"}'
```

The PNG is in `data[0].b64_json`. `/v1/models` advertises the image model when
its files are present. Other sizes, multiple images and URL responses are
rejected. LiteRT 2.3.0 runs in a separate `:images` process with renamed public
runtime libraries, keeping it separate from the Gemma runtime. Native output
stays in local `runtime.log` files; worker core dumps are disabled.

## Checks and source archive

`verify.py` runs real tokenizer, API, generation, contention, cancellation and
recovery checks on the phone. `validate_outputs.py`, run with the local export
environment, compares saved app tensors and images with CPU references:

```bash
.local/tensor-g5/tooling/.export-venv/bin/python \
  research/tensor-g5/app-overlay/validate_outputs.py \
  --device-results .local/tensor-g5/outputs/my-app-tests \
  --output-dir .local/tensor-g5/outputs/my-app-comparison
```

Run `pack.py --archive .local/tensor-g5/artifacts/name.zip` to bundle these
sources together with the compiler helpers. The ZIP includes checksums and
base-checkout metadata; SDK files, model files, generated source mirrors,
libraries, APKs, local configuration and run results are excluded.
