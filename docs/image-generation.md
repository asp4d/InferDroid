# Local image generation

Image generation is included in the regular **InferDroid** application,
`dev.inferdroid`, and its standard debug and release builds. The main screen's
**Generate images** action opens the image model and prompt controls. Its
API shares the existing authenticated localhost listener, default port **8080**.

## Prepare and build

Prepare the existing text and speech runtimes as described in the
[README](../README.md#reproduce-the-native-runtime), then run:

```bash
python3 scripts/prepare-image-runtime.py
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug
```

The image preparation script requires only Python's standard library. It
downloads pinned **LiteRT 2.3.0** Android AARs and matching C headers, the SDXS
tokenizer, and upstream notices. Every input is checked against
[`native/image-runtime-lock.json`](../native/image-runtime-lock.json).
Existing verified research downloads are reused when available;
`--offline` requires local inputs. The normal build requires no Tensor SDK,
Torch, Diffusers, source generation, alternate application ID, init script,
or Android build flag. Android Studio builds the same sources as Gradle.

Prepared libraries and headers are under `native/artifacts/images/`; downloads
are cached under `.deps/images/`. Generated tokenizer assets and notices are
ignored under `app/src/main/assets/`. Gradle checks the prepared files before
packaging, and the main CMake build compiles the image JNI bridge alongside
the text and audio bridges. Model weights are never included in the APK.

Install the normal debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.inferdroid/.MainActivity
```

## Select and remember a model

1. Open **Generate images**, then **Choose model folder**. Select the local
   folder containing `text_encoder.tflite`, `unet.tflite`, and `decoder.tflite`.
   The existing Pixel bundle is in
   `/storage/emulated/0/AIModels/sdxs-512-dreamshaper-tensor-g5/`.
2. The selected folder URI is displayed and saved with Android's persistent
   read grant. It remains preselected after reopening the app and becomes
   the picker's initial location. Canceling the picker keeps the selection.
   A provider that denies persistent access shows a warning; that selection
   lasts for the current launch, including rotation.
3. Tap **Load model** to copy and check the bundle in private app storage
   without generating an image. **Generate image** automatically imports it
   first when the selected source differs or its private files are missing.
   Selecting or restoring a folder starts no work.
4. Enter a prompt and signed 64-bit integer seed, generate, and use **Save PNG**
   to export through Android's document picker. Imported files can be reused
   without rereading the source folder.

This is a picker for the supported **SDXS Tensor G5** profile, model ID
`sdxs-512-dreamshaper-tensor-g5`. All three component sizes and SHA-256 hashes
must match [`profile.json`](../app/src/main/assets/image-generation/profile.json).
Arbitrary diffusion models are unsupported. Previously imported bundles
remain usable before an explicit source folder has been selected.

Imports use staging and an atomic directory switch. A failed import preserves
the previous working bundle and displays an error. Generation does not use
that previous bundle in place of a newly selected folder after import failure.
The verified private bundle's source is recorded as `images/imported_model`;
the last persistently selected folder is `images/model`.

The image screen uses the same cards, inputs, button styles, theme, and
English/Italian language settings as text generation. Labels, prompt/seed
validation, import status, worker progress, and notifications are translated.

## Pipeline and lifecycle

SDXS produces one **512×512 PNG** per request with one Euler step. Tokenization,
noise generation, scheduling, model execution, and PNG encoding run entirely
on the phone. Seeds reproduce this implementation's Java Gaussian noise;
the same numeric seed in PyTorch selects different initial tensors.

**Quality remains experimental:** the compiled denoiser's numerical validation
is still open. Image editing and additional diffusion-model profiles are
future work. Model source metadata identifies the pinned SDXS revision and
license; preparation packages its model card together with the AAR licenses
and third-party notices. Weights are supplied separately.

`InferenceService` owns the image controller and the existing shared `WorkGate`.
Import and generation hold that slot across both phases, serializing image,
chat, ASR, and TTS work. **Cancel**, **Unload / Stop**, and notification actions
use the same service lifecycle. An atomic import finishes before releasing
admission; cancellation prevents subsequent generation.

The image worker runs in the app's private **`:images` process** so LiteRT
2.3.0 and Gemma's pinned LiteRT-LM ABI remain separate. The two image runtime
libraries are packaged under distinct filenames and loaded only by that
worker. This is process isolation inside the same application. Runtime logs
stay in local image-run directories, and worker core dumps are disabled.

## Local API

Load/import the image bundle through the image screen, start **Local OpenAI
API** from the normal main screen, and use its displayed key:

```bash
curl http://127.0.0.1:8080/v1/images/generations \
  -H "Authorization: Bearer $INFERDROID_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"sdxs-512-dreamshaper-tensor-g5","prompt":"A red robot reading in a garden","seed":0,"n":1,"size":"512x512","response_format":"b64_json"}'
```

The PNG is returned in `data[0].b64_json`. `/v1/models` advertises the image
model when its private files are present. The endpoint uses that imported
bundle. Other sizes, multiple images, URL responses, and unsupported models
are rejected. Missing models return 503; shared-work contention returns 429.
Authentication, localhost binding, CORS, cancellation, and server controls
are shared with [the other local API endpoints](local-api.md).

## Verification

Build and install the normal app and test APKs, then run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/test-device-images.py --ui-only \
  --output-dir .local/tensor-g5/outputs/image-ui-check
python3 scripts/test-device-images.py \
  --output-dir .local/tensor-g5/outputs/image-full-check
```

The UI checks cover saved selection, picker cancellation, reopening, rotation,
temporary access, prompt/seed validation, failed-import preservation, and
English/Italian resources. They restore the prior saved selection and do not
replace model files or generate images. The full suite requires the imported
SDXS bundle and additionally checks eight tokenizer fixtures, authentication,
API/model discovery, real PNG generation, shared admission, disconnect
cancellation, and recovery. It uses the regular instrumentation runner with
`-e suite images`; the UI-only option adds `-e ui_only true`.

The existing lifecycle/API tests use the same APK and runner without a suite
argument. See [the standard test command](../README.md#build-and-device-verification).
Allow GrapheneOS Network permission for localhost API checks.

Model export, compilation, and numerical comparison remain development tools
under [the research directory](../research/tensor-g5/README.md). Saved device
tensors can be compared with CPU references using
`research/tensor-g5/tooling/validate_app_outputs.py`. SDKs, model weights,
compiled components, detailed results, and older archives stay under `.local/`.

On 2026-10-17, the regular debug/release builds and lint passed, as did all 40
lifecycle/API tests and the complete image suite on the Pixel 10. Real picker
access, automatic import/generation, and selection after a cold restart were
verified in `dev.inferdroid`. Gemma NPU text generation then passed, followed
by image generation while Gemma remained loaded and verified. Generated
reports, APK inspection records, and screenshots stay under ignored `.local/`.
