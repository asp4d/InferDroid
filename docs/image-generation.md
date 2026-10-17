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
   If you return to the main screen during an import, **Start server** stays
   disabled while the shared work slot is busy and re-enables automatically
   when the import finishes, including after an import error.
4. Enter a prompt and signed 64-bit integer seed, generate, and use **Save PNG**
   to export through Android's document picker. Imported files can be reused
   without rereading the source folder.
5. **Unload / Stop images** stops image work and releases the image worker's
   remaining memory. It keeps the imported model files, selected folder, and
   last generated PNG. The API listener and chat/audio engines stay available.
   The next UI or API image request starts the runtime automatically; a new
   import is unnecessary unless you select a different folder.

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
The prompt and seed fields have permanent labels and descriptions above them,
so their purpose remains visible while entering values. The seed controls the
starting noise: keep the prompt, model, and seed to repeat a result, or change
the seed for another variation. The default `0` is a fixed seed.

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

**Unload / Stop images** affects only image work. During an import, it waits
for the atomic copy to finish and skips generation. During generation, it
cancels that request, unbinds the worker, and stops the app's private image
process, including any cached native-library and allocator memory. Cleanup
runs off the UI thread and holds the shared work slot until complete. The
button becomes available again after another load or generation request.
The main screen's **Unload / Stop** also releases the image process while
stopping the server and all other engines.

Native model objects are already destroyed after each pipeline stage;
**Load model** imports and verifies files in storage. Unloading preserves
those files and their `GET /v1/models` entry. It cancels an active image API
request with `image_cancelled`, while later requests load the runtime normally.

The image worker runs in the app's private **`:images` process** so LiteRT
2.3.0 and Gemma's pinned LiteRT-LM ABI remain separate. The two image runtime
libraries are packaged under distinct filenames and loaded only by that
worker. This is process isolation inside the same application. Runtime logs
stay in local image-run directories, and worker core dumps are disabled.

## Local API

Load/import the image bundle through the image screen, start **Local OpenAI
API** from the normal main screen, and use its displayed key:

```bash
curl --max-time 600 http://127.0.0.1:8080/v1/images/generations \
  -H "Authorization: Bearer $INFERDROID_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"sdxs-512-dreamshaper-tensor-g5","prompt":"A red robot reading in a garden","seed":0,"n":1,"size":"512x512","response_format":"b64_json"}'
```

The endpoint follows the [OpenAI Images generation request and response format](https://developers.openai.com/api/reference/resources/images/methods/generate)
for the supported local subset. It returns:

```json
{"created":1792231200,"data":[{"b64_json":"<base64-encoded PNG>"}]}
```

`GET /v1/models` advertises `sdxs-512-dreamshaper-tensor-g5` with text input
and image output modalities when its private files are present. The endpoint
uses that imported bundle without requiring Gemma to be configured or loaded.
All generation runs on the phone; no OpenAI account or cloud key is needed.

| Parameter | Supported values and defaults |
| --- | --- |
| `prompt` | Required nonblank string, at most 8192 characters, without null characters |
| `model` | `sdxs-512-dreamshaper-tensor-g5`; defaults to this model when omitted |
| `n` | `1` (default) |
| `size` | `512x512` (default) or `auto`; both produce 512×512 |
| `response_format` | `b64_json` (default); PNG bytes in `data[0].b64_json` |
| `quality` | `standard` or `auto`; both use the fixed one-step SDXS profile |
| `output_format` | `png` |
| `background` | `opaque` or `auto`; output has an opaque background |
| `stream` | `false` (default); the response arrives after generation |
| `user` | Optional string metadata; not passed to the model |
| `seed` | Optional local extension: signed 64-bit integer; random when omitted |

Optional fields set to JSON `null` use their omitted behavior. The minimal
request is `{"prompt":"A red robot reading in a garden"}`. For repeatable
results use an explicit `seed`; OpenAI SDKs can send this extension using
`extra_body={"seed": 0}`.

Unsupported parameter values return 400 with an OpenAI-style `error` object
and the offending `param`; unknown model IDs return 404. Missing models
return 503 with `image_model_unavailable`; shared-work contention returns
429 with `engine_busy` and `Retry-After: 1`. Other sizes, multiple images,
URL responses, transparent backgrounds, image streaming, and quality levels
that require a different pipeline are rejected. `/v1/images/edits` and
`/v1/images/variations` are not implemented and return 404.

### OpenAI Python client

Install the `openai` Python package in your client environment and set
`INFERDROID_KEY` to the key copied from the app. On a development computer,
first run `adb forward tcp:8080 tcp:8080`; on the phone use localhost directly.

```python
import base64
import os
from pathlib import Path
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:8080/v1",
    api_key=os.environ["INFERDROID_KEY"],
    timeout=600.0,
    max_retries=0,
)
result = client.images.generate(
    model="sdxs-512-dreamshaper-tensor-g5",
    prompt="A red robot reading in a garden",
    n=1,
    size="512x512",
    response_format="b64_json",
)
Path("generated.png").write_bytes(base64.b64decode(result.data[0].b64_json))
```

The client must support the Images API and base64 responses. Adding this model
to a chat-only client's model list does not add image generation to that client.
Authentication, localhost binding, CORS, disconnect cancellation, and server
controls are shared with [the other local API endpoints](local-api.md).

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
temporary access, prompt/seed validation, failed-import preservation,
English/Italian resources, and main-screen server controls after image imports.
The server regression captures the main screen's busy state during an import,
checks that completion re-enables **Start server**, then starts the API through
that button without a chat selection and verifies model discovery and stopping.
They restore the prior saved selection and server settings and do not
replace model files or generate images. They also check translated unload
controls, preservation of imported files/selection, and unloading during an
import while keeping the server available. The full suite requires the imported
SDXS bundle and additionally checks eight tokenizer fixtures, authentication,
API/model discovery, real PNG generation, shared admission, disconnect
cancellation, idle/active image unload, process release, and generation after
unload. It uses the regular instrumentation runner with
`-e suite images`; the UI-only option adds `-e ui_only true`.

The existing lifecycle/API tests use the same APK and runner without a suite
argument. They also include four image-generation transport checks using a
small PNG fixture without model weights: validation/discovery, response/default
compatibility, shared admission, and disconnect/stop cancellation with recovery.
These are separate from the full suite's real 512×512 model checks.
See [the standard test command](../README.md#build-and-device-verification).
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

The image API compatibility update was also verified on 2026-10-17: debug
assembly, lint, all **44 lifecycle/API tests**, and the full real-model image
suite passed on the Pixel 10. Real requests included `quality: "auto"`,
`output_format: "png"`, `background: "auto"`, and `stream: false`. The existing
immediate server restart test exposed a listener shutdown race; shutdown now
waits briefly for the accept thread to exit before returning. Final logs,
the device report, and the debug APK checksum are retained under ignored
`.local/tensor-g5/outputs/image-api-20261017/`.

The main-screen startup regression was reproduced and fixed on 2026-10-17.
Returning while an image import was pending could leave **Start server**
disabled after completion because the main screen did not observe image-state
changes. It now subscribes while connected and detaches when leaving. The new
UI regression failed before the fix and passed afterward, including starting
and stopping the image API through the main-screen buttons without a chat
selection. Build, lint, all 44 lifecycle/API tests, and the full real-image
suite passed again. Before/after reports and the final APK checksum are under
ignored `.local/tensor-g5/outputs/image-server-start-20261017/`.

**Unload / Stop images** was verified on the Pixel 10 on 2026-10-17. Debug
assembly, lint, all 44 lifecycle/API tests, and the full image suite passed.
Checks covered the English/Italian control, unloading during import, idle
process release, cancellation of an active image API request, preservation
of model files/folder/last PNG, continued API model discovery, unchanged
chat/audio engine state, and real PNG generation after unloading. Logs,
reports, and the debug APK checksum are under ignored
`.local/tensor-g5/outputs/image-unload-20261017/`.
