<p align="center">
  <img src="docs/assets/inferdroid-logo.svg" alt="InferDroid logo" width="180" height="180">
</p>

# InferDroid

A native **Android Studio / Java / JNI** app for the proven Pixel 10 Gemma 4
E2B NPU path and independent **offline speech recognition** using sherpa-onnx
and multilingual Whisper tiny on CPU. A Java foreground service retains the
models between requests and serves an authenticated **OpenAI-compatible
localhost API**, including live chat SSE and audio-file transcription. The UI
shows generated text, speech transcripts, backend status, model/server controls,
native diagnostics, and customizable themes and languages. Work runs off the
UI thread and shares one active-work slot. TTS, vision, image generation, and
network tools are later work.

**Milestone 1 passed on the Pixel 10 / GrapheneOS on 2026-10-08.** Two
consecutive Java → JNI → LiteRT-LM runs returned Gemma's introduction to the
UI. Logs confirmed the packaged Google Tensor dispatch, dispatch delegate
kernels, and `/vendor/lib64/libedgetpu_litert.so`. The first run took 5.674 s
including 2.612 s initialization, with 0.27 s TTFT and 17.10 decode tokens/s;
the repeat took 3.997 s with 18.88 decode tokens/s.

**Milestone 2 passed on the same device on 2026-10-08.** Six real service
requests returned generated text. Reusing the loaded engine took 2.414–2.535 s
in the foreground. Background execution, cancellation and recovery, stopping
during load/generation, notification controls, descriptor release, and reload
were verified. See [the device verification record](docs/milestone-verification.md)
and [service lifecycle](docs/service-lifecycle.md).

**Milestone 3 passed on the same device on 2026-10-10.** Real HTTP and live
SSE completions, conversation history, output caps, background serving, busy
responses, and cancellation/recovery used the retained Tensor G5 engine.
The live introduction arrived in 43 text chunks, with first content at
0.218 s and total time 2.675 s. Ten lifecycle/API instrumentation tests also
passed. See [local API usage and limits](docs/local-api.md) and
[the verification record](docs/milestone-verification.md).

**Milestone 4 passed on the same device on 2026-10-10.** Public speech
recordings were transcribed through `/v1/audio/transcriptions` and the UI.
The final APK's first 6.625-second WAV took 1.41 seconds including speech
initialization; warm WAV requests took 0.46–1.42 seconds. MP3, M4A/AAC, FLAC, Ogg/Vorbis,
WebM/Opus, background serving, cancellation/recovery, and shared chat/speech
429 responses passed. Sixteen lifecycle/API/audio tests passed, and Gemma
NPU/SSE regression checks passed with both models loaded. Physical ASR accuracy
checks used English recordings; Italian is supported by the multilingual model
but has not yet been measured on an Italian recording.

Read [the original handoff](pixel_local_ai_server_codex_handoff.md),
[the verified native strategy](docs/native-integration.md), and
[speech setup and limits](docs/speech-recognition.md) for exact source APIs,
hashes, storage decisions, and licensing notes.

## Acknowledgements & Development

This project was architected, verified on physical hardware, and maintained by Alessandro Spadini, 
with code generation and refactoring assisted by AI development tools (IntelliJ IDEA / 
Android Studio / OpenAI Codex / Gemini).

## Open and build in Android Studio

1. Open this repository's **root directory** in Android Studio.
2. Use **JDK 21** for Gradle under Settings → Build, Execution, Deployment →
   Build Tools → Gradle, matching the command-line examples below. The Java
   compiler toolchain is pinned to 21; source/bytecode compatibility stays at
   Java 17 and available Java APIs remain controlled by Android's SDK.
3. Install Android SDK Platform **37**, the SDK Build Tools version requested
   by AGP during sync, CMake **3.22.1**,
   Platform Tools, and NDK **30.0.14904198 (r30-beta1)**. Enable preview/show
   package details in SDK Manager for this exact NDK. Do not replace the pinned
   inference stack with a newer LiteRT dependency.
4. Run both native build scripts below once from Android Studio's Terminal.
   LiteRT uses Bazel; sherpa-onnx uses CMake. Gradle/CMake builds both JNI bridges.
5. Sync Gradle, select the `app` run configuration and the physical Pixel 10.
   Android Studio **Build APK(s)** / **Run** builds the Java and JNI code and
   packages the prepared native runtime. Run launches the UI, but does not
   automatically load a model, submit a prompt, or start the HTTP listener.
   Use the in-app controls below.

Gradle Wrapper is pinned to **9.8.1** in
[`gradle/wrapper/gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties).
Android Gradle Plugin is **9.4.1**, declared in the version catalog
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). Application ID:
**`dev.inferdroid`**. Only **`arm64-v8a`** is packaged. Minimum Android API: 31;
compile API: **37**; target API: **36**.

`local.properties` is machine-specific and ignored. For an SDK-installed NDK:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

If the exact NDK was unpacked separately, also set:

```properties
inferdroid.ndkPath=/absolute/path/to/android-ndk-r30-beta1
```

On this workstation, `local.properties` points at `/opt/android-sdk` and the
persistent, ignored project directory `.deps/toolchains/android-ndk-r30-beta1`.
The verified Bazel executable is also available in `.deps/tools/`.
The installed Java 21 runtime is `/home/asp4d/.jdks/jbr-21.0.11`; Android
Studio's `GRADLE_LOCAL_JAVA_HOME` already resolves to it on this workstation.
The shell's default Java and Studio's bundled runtime are Java 25, so set
`JAVA_HOME` explicitly for the commands below. JDK 21 can compile this Android
app without requiring Java 21 on the phone. See
[Android's JDK guidance](https://developer.android.com/build/jdks) and
[Gradle's compatibility table](https://docs.gradle.org/current/userguide/compatibility.html).

## Reproduce the native runtime

Host tools: Linux x86_64, Bash, JDK 21, `git`, `curl`, `python3`, `rg`,
`sha256sum`, CMake, `make`, `unzip`, and **Bazel 7.6.1** or Bazelisk
(reads upstream `.bazelversion`).
Allow roughly **125 GB** free disk for a complete first source build; the
reference recipe's first-build estimate is tens of minutes.

```bash
export JAVA_HOME=/absolute/path/to/jdk-21
export ANDROID_HOME=/absolute/path/to/Android/Sdk
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r30-beta1
export BAZEL=/absolute/path/to/bazel-7.6.1-linux-x86_64
./scripts/build-native.sh
./scripts/build-speech.sh
./gradlew :app:assembleDebug :app:lintDebug
```

With Bazelisk on PATH, omit `BAZEL`. Optional `NATIVE_JOBS` (default 6) and
`NATIVE_RAM_MB` (default 12000) bound build concurrency. Bazel's generated tree
defaults to `/tmp/inferdroid-bazel-$UID` to avoid encrypted-home filename limits;
`NATIVE_BUILD_ROOT` can select another normal filesystem. Downloaded runtime
source lives in ignored `.deps/LiteRT-LM/`.

Exact commands using the tools already prepared in this workspace:

```bash
export JAVA_HOME=/home/asp4d/.jdks/jbr-21.0.11
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK_HOME="$PWD/.deps/toolchains/android-ndk-r30-beta1"
export BAZEL="$PWD/.deps/tools/bazel-7.6.1-linux-x86_64"
./scripts/build-native.sh
./scripts/build-speech.sh
./gradlew :app:assembleDebug :app:lintDebug
```

The script automatically downloads the pinned LiteRT-LM checkout, resolves
its pinned source dependencies, verifies the required upstream LFS binary,
builds dispatch and the adapter, and places these files at the exact APK input:

```text
native/artifacts/arm64-v8a/libinferdroid_litertlm.so
native/artifacts/arm64-v8a/libLiteRtDispatch_GoogleTensor.so
native/artifacts/arm64-v8a/libGemmaModelConstraintProvider.so
native/artifacts/arm64-v8a/VERSIONS.txt
native/artifacts/arm64-v8a/SHA256SUMS
```

CMake fails explicitly if any runtime library is missing. It does not build a
stub APK. Rerun `build-native.sh` after changing `native/runtime_adapter.cc`.
Java/UI/JNI-only changes use the normal Android Studio/Gradle build.

`build-speech.sh` independently builds sherpa-onnx **1.13.8**, using its matched
ONNX Runtime **1.28.2** Android binary. CPU ASR is explicitly selected; TTS and
diarization are disabled. Prepared speech libraries and the matching C header
live in `native/artifacts/speech/`. This build does not replace the G5 stack.
Both native build scripts package upstream dependency/license notices. See
[the speech runtime pins and checksums](docs/speech-recognition.md).

## Exact separate downloads

Native artifacts and model weights are excluded from Git. The native script
performs the first two downloads automatically; do not manually install random
`.so` files in `jniLibs`.

| Input | Source URL | Exact local destination |
| --- | --- | --- |
| LiteRT-LM source | [GitHub tag v0.14.0-alpha.0](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.14.0-alpha.0), verified commit `0b98b80e1d846af27d10b8ab395645119cb231e4` | `.deps/LiteRT-LM/` |
| Required constraint provider | [Pinned upstream LFS binary](https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/0b98b80e1d846af27d10b8ab395645119cb231e4/prebuilt/android_arm64/libGemmaModelConstraintProvider.so) | `.deps/LiteRT-LM/prebuilt/android_arm64/libGemmaModelConstraintProvider.so` |
| NDK r30-beta1, Linux | [Google NDK archive](https://dl.google.com/android/repository/android-ndk-r30-beta1-linux.zip) | Unpack to `.deps/toolchains/android-ndk-r30-beta1/` and set `inferdroid.ndkPath` / `ANDROID_NDK_HOME`; alternatively SDK Manager installs it under `$ANDROID_HOME/ndk/30.0.14904198/` |
| Bazel 7.6.1, Linux x86_64 | [Official Bazel binary](https://github.com/bazelbuild/bazel/releases/download/7.6.1/bazel-7.6.1-linux-x86_64) | For example `.deps/tools/bazel-7.6.1-linux-x86_64`; make executable, set `BAZEL` |
| Bazel checksum | [Official SHA-256](https://github.com/bazelbuild/bazel/releases/download/7.6.1/bazel-7.6.1-linux-x86_64.sha256) | `.deps/tools/bazel-7.6.1-linux-x86_64.sha256` |
| Model, if not already on Pixel | [Exact G5 model download](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_Google_Tensor_G5.litertlm) | Host: `.deps/models/gemma-4-E2B-it_Google_Tensor_G5.litertlm`; then shared model directory on Pixel below |

NDK Linux archive SHA-1 from Google's repository metadata:
`26b746e5a1e7ac3371f2a862a2f52a7c0740aa8a`.
Bazel binary SHA-256:
`ac6249d1192aea9feaf49dfee2ab50c38cee2454b00cf29bbec985a11795c025`.
Constraint-provider SHA-256:
`985ec5778144730b80666a0f71f1e06038eb07dd1a3e941c6ab7f963eda00a8e`.

The constraint provider is supplied as a binary-only upstream dependency at
this tag. This limitation and redistribution notices are explained in
[native-integration.md](docs/native-integration.md#licensing-and-source-limitations).
The app does not package the Tensor SDK compiler or the phone's proprietary
`libedgetpu_litert.so`.

## Install and prepare the first Pixel test

Build output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.inferdroid/.MainActivity
```

The existing model was confirmed on the connected Pixel at:

```text
/storage/emulated/0/AIModels/litert-npu/gemma-4-E2B-it_Google_Tensor_G5.litertlm
```

**No model deployment is necessary on this Pixel.** In InferDroid, tap
**Choose model**, browse internal storage → `AIModels` → `litert-npu`, and
select that exact file. A persistent `content://` URI is displayed. LiteRT-LM
uses its seekable descriptor directly, without duplicating the 3 GB file.
Do not enter the raw shared-storage pathname and assume scoped-storage access.
Native libraries from the old standalone directory are not used by the app.

If another Pixel needs the model, download it into the host destination above
and deploy only the model:

```bash
adb shell mkdir -p /sdcard/AIModels/litert-npu
adb push .deps/models/gemma-4-E2B-it_Google_Tensor_G5.litertlm /sdcard/AIModels/litert-npu/
```

Then select it in the document picker. The model is never bundled in the APK.
If a provider returns a pipe/non-seekable descriptor, the app explains the
failure; choose the local file through the system Files provider instead.

## Use the persistent inference service

1. Select the G5 model through **Choose model**. An existing saved selection
   can be reused after an APK update.
2. Tap **Load model** to initialize the NPU without submitting a prompt.
   Alternatively, **Run** loads it automatically when necessary. On Android
   13+, the first use asks for notification permission so the foreground
   service status and controls can be displayed.
3. Leave `Hello, briefly introduce yourself.` as the prompt and tap **Run**.
   Requests are serialized; another Run is disabled while work is active.
4. The model stays loaded after generation. You can leave the Activity and
   return to its current status/output while the foreground service runs.
5. **Cancel** requests cancellation of generation through upstream
   `Conversation::CancelProcess()`. A fresh conversation is used for each
   request, so cancellation does not reuse a partially updated session.
6. **Unload / Stop** cancels active generation, waits for native work to drain,
   closes the HTTP listener if running, releases the model/descriptor, and
   removes the foreground notification.
   These controls are also available in the notification where applicable.

Status distinguishes **NPU selected**, **NPU initialized**, and **generation
verified**. Initialization alone does not claim a successful NPU execution.
Failures remain visible and never trigger a CPU retry.

NPU initialization has no upstream cancellation API at this pin. Unload during
loading waits for initialization to return, then releases the engine without
starting generation. A partial wake lock is held only during native work,
with a ten-minute bound; the loaded idle model does not keep the CPU awake.
If Android kills the process, reopening the app returns to an unloaded engine;
the service does not automatically reload a 3 GB model at boot or after a kill.

The private service uses Android's `specialUse` foreground type with a manifest
explanation for local inference, as described in the
[foreground service documentation](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).
Notification denial does not grant additional privileges: Android can hide the
notification, and the app shows that actual permission state.

## Use the localhost API

Select the chat model or import the speech model, scroll to **Local OpenAI API**,
and press **Start server**.
Keep **Require local API key** enabled and use **Copy key** to configure a
trusted client. The model loads on the first request; **Load model** can
initialize it beforehand. The server stays available while the Activity is
backgrounded.

| Client setting | Default |
| --- | --- |
| OpenAI base URL | `http://127.0.0.1:8080/v1` |
| Chat model ID | `gemma-4-E2B-it_Google_Tensor_G5` |
| Speech model ID | `sherpa-onnx-whisper-tiny` |
| Authorization | `Bearer <copied-local-key>` |
| Endpoints | `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/audio/transcriptions` |

Use `stream: true` for live SSE. Both UI and API requests use the same single
inference slot across chat and speech; an overlap returns an OpenAI-style **429** error. Port, key,
and optional CORS settings persist and can be changed while the listener is
stopped. **Stop server** cancels its active client request and retains the
loaded models for UI use. **Unload / Stop** stops the server and drains/unloads
both engines. Speech can be served without choosing or loading Gemma.

**GrapheneOS upgrade:** allow **Network** under InferDroid's app permissions;
localhost sockets require it. This permission was disabled when the milestone
2 installation first received this update. Clients need their own Network
permission and must permit cleartext localhost HTTP. The server binds only
to `127.0.0.1`, and it makes no outbound network requests. CORS is optional
and disabled initially; token authentication is enabled initially because
other apps on the phone can reach loopback.

See [local-api.md](docs/local-api.md) for supported text messages and sampling
parameters, error behavior, limits, CORS, and complete `curl` examples through
`adb forward tcp:18080 tcp:8080`. File transcription uses multipart uploads;
see [speech API parameters](docs/speech-recognition.md#openai-compatible-transcription-endpoint).
Gemma multimodal audio/image input, TTS, tools, structured output, and
`/v1/completions` are later work.

## Use offline speech recognition

Prepare and deploy the separate, approximately **99 MiB** Whisper model:

```bash
./scripts/prepare-speech-model.sh
adb shell mkdir -p /sdcard/AIModels/sherpa-onnx-whisper-tiny
adb push .deps/speech/models/sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx /sdcard/AIModels/sherpa-onnx-whisper-tiny/
adb push .deps/speech/models/sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx /sdcard/AIModels/sherpa-onnx-whisper-tiny/
adb push .deps/speech/models/sherpa-onnx-whisper-tiny/tiny-tokens.txt /sdcard/AIModels/sherpa-onnx-whisper-tiny/
```

In **Offline speech recognition**, tap **Import speech model folder** and select
that folder. The app copies and checksum-verifies the three files in private
storage; model weights stay out of the APK. Then **Choose audio file**, optionally
enter `en` or `it` (empty detects language), and tap **Transcribe audio**.
**Load speech** prepares it first; **Unload speech** releases only speech.
The top **Cancel** and **Unload / Stop** also control active speech work.

Input is limited to **25 MiB / 120 seconds**, with mono/stereo WAV and the
device's supported compressed codecs. The API returns JSON, plain text, or
verbose JSON with duration/language. Longer recordings use consecutive
25-second chunks; words at boundaries can lose accuracy. Cancellation waits
for the current native decode to drain. No microphone permission is required.
See [speech-recognition.md](docs/speech-recognition.md) for hashes, licensing,
supported parameters, codec limits, and a multipart `curl` example.

## UI Customization, Themes & Languages

InferDroid includes a dedicated **UI Settings** screen (accessible via the gear icon in the top header) for customizing the visual theme and language preferences:

* **Themes**:
  * **Light**: Classic clean light interface.
  * **OLED Dark**: Pure pitch-black (`#000000`) dark theme designed to save energy on OLED displays.
  * **Auto (System Light/Dark)**: Automatically switches between Light and OLED Dark based on system dark mode settings.
  * **Gruvbox Light**: Retro warm light palette with soft cream background (`#fbf1c7`) and dark charcoal text (`#3c3836`).
  * **Gruvbox Dark**: Retro warm dark palette with dark background (`#282828`) and soft cream text (`#ebdbb2`).
  * **Auto Gruvbox**: Automatically switches between Gruvbox Light and Gruvbox Dark based on system dark mode settings.
* **Language Selection**:
  * Per-app language management supporting **System Default**, **English**, and **Italiano** (Italian), with extensible architecture for adding additional languages in the future.

## Capture Tensor G5 evidence

Keep **Verbose native diagnostics** enabled for this first test. Start a full
capture before pressing Run so initialization, vendor, and crash messages are
preserved:

```bash
adb logcat -v threadtime > /tmp/inferdroid-first-test.log
```

In the app, leave `Hello, briefly introduce yourself.` as the prompt and tap
**Run**. Generated text and diagnostics appear when the native call returns.
Stop logcat with
Ctrl+C after the run, then inspect:

```bash
rg 'GoogleTensor|dispatch_delegate_kernel|DispatchDelegate|SouthBound|libedgetpu_litert|InferDroid|FATAL|Fatal signal' /tmp/inferdroid-first-test.log
```

The APK's observed evidence is equivalent to the handoff's dispatch route:

```text
Loading shared library: .../libLiteRtDispatch_GoogleTensor.so
Found GoogleTensorOptions
[dispatch_delegate_kernel.cc:203] Found async dispatch capabilities
SouthBound symbols resolved by 'libedgetpu_litert.so'
```

The diagnostics also report the actual loaded dispatch/vendor paths, engine
initialization time, and upstream benchmarks. A loaded vendor library alone
does not prove an NPU inference; require generated text and delegate evidence.
Supporting XNNPACK operations and the documented generic accelerator warnings
can coexist with valid Google Tensor execution. A real initialization failure
is surfaced; the app does not retry on CPU.

On this 12 GB phone, the handoff already demonstrates successful inference.
If allocation specifically fails for the large dma-buf, the reference project
recommends closing other apps or rebooting and testing after boot. This is an
allocation diagnostic, not a claim that GrapheneOS or Tensor G5 cannot work.

The initial project and build-tool upgrade are already in Git. The physical
NPU verification above establishes the milestone 1 baseline before service work.

## Branding assets

The supplied logo is kept as [docs/assets/inferdroid-logo.svg](docs/assets/inferdroid-logo.svg).
It is used in this README, the app header, and the launcher icons. Android
resources include adaptive foreground/background layers and a monochrome
variant for themed icons. Adaptive icons support the launcher's different
shapes and screen densities on all supported Android versions (API 31+).

Generated PNGs are included in the project; normal Android Studio builds need
no image tools. To regenerate them after editing the SVG, install Inkscape
and run from the repository root:

```bash
python3 scripts/generate-icons.py
```

## Build and device verification

Assembly and lint were rerun with JDK 21, Gradle 9.8.1 / AGP 9.4.1 /
compile SDK 37 for milestone 4 on 2026-10-10.

- Native adapter and Google Tensor dispatch built from the pinned sources.
- Independent ASR-only sherpa-onnx runtime built from pinned sources, with the
  matching ONNX Runtime and license notices. The G5 stack remains pinned.
- Java, CMake/JNI, debug APK assembly, and Android lint passed (0 errors).
  Lint reports 12 warnings, including the existing theme/AppCompat resources,
  dependency/catalog notices, arm64-only targeting, and target API 36.
- APK inspection confirmed seven arm64 native libraries and no
  `.litertlm`/`.onnx` model. The milestone 4 debug APK is 41.1 MB (39.2 MiB).
- `adb install -r` succeeded on the connected Pixel 10. `MainActivity` launched
  successfully and its UI was inspected. Android confirmed extracted native
  libraries, arm64 ABI, and `libedgetpu_litert.so` in the app's vendor library
  access list. Device page size is 4096 bytes.
- **Milestone 1: two physical app inferences passed**, producing text in Java
  with Google Tensor delegate and EdgeTPU evidence.
- **Milestone 2: six real foreground-service inferences passed**, including
  generation while the Activity was backgrounded and successful reuse after
  cancellation. One engine initialization served the first four successful
  requests and a cancelled request. Unload removed the model's memory mappings
  and file descriptors; reload worked afterward. No inference wake lock was
  held while the model was idle. Details are in the verification record.
- **Milestone 3: physical HTTP and SSE requests passed**, including history
  with a system prompt, an eight-token output cap with `finish_reason: length`,
  real 429 rejection, serving in the background, and native cancellation on
  client disconnect followed by successful reuse. NPU evidence remained the
  same. Measurements are in the verification record.
- **Milestone 4: real offline file transcription passed**, using the public
  English test recordings distributed with the speech model. UI model import,
  file selection, encoded formats, background serving, shared chat/speech 429,
  disconnect cancellation/reuse, and the Gemma NPU/SSE regression were checked.
  Full Unload / Stop released both engines and removed foreground status.
- **Sixteen device instrumentation tests passed** for the original lifecycle,
  UTF-8 boundaries, authentication/validation, history/parameter translation,
  live streaming, UI/API contention, disconnect/recovery, CORS/port conflict,
  stop/restart/key rotation, PCM/resampling/duration bounds, multipart validation,
  transcription formats, cross-engine contention, speech cancellation/recovery,
  and stopping during load/import. They use fake engines to control timing and
  the real Java audio decoder; physical CPU ASR and NPU execution were verified
  separately through the HTTP service.

To rerun the lifecycle/API tests on a connected Android device, allow the
GrapheneOS Network permission first, then:

```bash
export JAVA_HOME=/home/asp4d/.jdks/jbr-21.0.11
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.inferdroid.test/dev.inferdroid.engine.EngineManagerInstrumentation
```

Expected result: `Lifecycle/API tests: 16 passed, 0 failed.` The runner uses only
Android platform APIs and adds no test dependencies or fake backend to the
application APK. Instrumentation restarts the target app process; reopen
InferDroid afterward and press Run or Start server for a real NPU request.

## Author

Developed by **Alessandro Spadini**:
- **Website**: [spadini.dev](https://spadini.dev)
- **GitHub**: [@asp4d](https://github.com/asp4d)

## License

Copyright © 2026 Alessandro Spadini.

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.
