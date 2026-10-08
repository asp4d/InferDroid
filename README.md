<p align="center">
  <img src="docs/assets/inferdroid-logo.svg" alt="InferDroid logo" width="180" height="180">
</p>

# InferDroid

A minimal **Android Studio / Java / JNI** app for the proven Pixel 10 Gemma 4
E2B NPU path. A Java foreground service owns the native engine and keeps the
selected model loaded between requests. The test UI shows generated text,
backend status, and native diagnostics. Inference runs on a worker thread.
HTTP/OpenAI, ASR/TTS, vision, image generation, and network tools are later work.

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

Read [the original handoff](pixel_local_ai_server_codex_handoff.md) and
[the verified native strategy](docs/native-integration.md) for exact source
APIs, hashes, storage decisions, and licensing notes.

## Open and build in Android Studio

1. Open this repository's **root directory** in Android Studio.
2. Use **JDK 17** for Gradle under Settings → Build, Execution, Deployment →
   Build Tools → Gradle, matching the command-line examples below.
3. Install Android SDK Platform **37**, the SDK Build Tools version requested
   by AGP during sync, CMake **3.22.1**,
   Platform Tools, and NDK **30.0.14904198 (r30-beta1)**. Enable preview/show
   package details in SDK Manager for this exact NDK. Do not replace the pinned
   inference stack with a newer LiteRT dependency.
4. Run the native source build below once from Android Studio's Terminal.
   The upstream runtime uses Bazel; CMake builds the app's JNI bridge.
5. Sync Gradle, select the `app` run configuration and the physical Pixel 10.
   Android Studio **Build APK(s)** / **Run** builds the Java and JNI code and
   packages the prepared native runtime. Run launches the UI, but does not
   automatically load a model or submit a prompt. Use the in-app controls below.

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

## Reproduce the native runtime

Host tools: Linux x86_64, Bash, JDK 17, `git`, `curl`, `python3`, `rg`,
`sha256sum`, and **Bazel 7.6.1** or Bazelisk (reads upstream `.bazelversion`).
Allow roughly **125 GB** free disk for a complete first source build; the
reference recipe's first-build estimate is tens of minutes.

```bash
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/Android/Sdk
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r30-beta1
export BAZEL=/absolute/path/to/bazel-7.6.1-linux-x86_64
./scripts/build-native.sh
./gradlew :app:assembleDebug :app:lintDebug
```

With Bazelisk on PATH, omit `BAZEL`. Optional `NATIVE_JOBS` (default 6) and
`NATIVE_RAM_MB` (default 12000) bound build concurrency. Bazel's generated tree
defaults to `/tmp/inferdroid-bazel-$UID` to avoid encrypted-home filename limits;
`NATIVE_BUILD_ROOT` can select another normal filesystem. Downloaded runtime
source lives in ignored `.deps/LiteRT-LM/`.

Exact commands using the tools already prepared in this workspace:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK_HOME="$PWD/.deps/toolchains/android-ndk-r30-beta1"
export BAZEL="$PWD/.deps/tools/bazel-7.6.1-linux-x86_64"
./scripts/build-native.sh
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
   releases the model/descriptor, and removes the foreground notification.
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

Assembly and lint were rerun with the current Gradle 9.8.1 / AGP 9.4.1 /
compile SDK 37 configuration on 2026-10-08.

- Native adapter and Google Tensor dispatch built from the pinned sources.
- Java, CMake/JNI, debug APK assembly, and Android lint passed (0 errors).
  Lint's two warnings are intentional arm64-only targeting and target API 36
  below the newest platform. The native runtime versions remain pinned.
- APK inspection confirmed exactly four arm64 native libraries and no
  `.litertlm` model. The service debug APK is approximately 25 MB.
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
- **Three device instrumentation tests passed** for serialization/reuse,
  concurrent cancellation/recovery, and stopping during initialization. These
  use a fake engine to exercise lifecycle timing; real NPU execution was
  verified separately through the app UI.

To rerun the lifecycle tests on a connected Android device:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.inferdroid.test/dev.inferdroid.engine.EngineManagerInstrumentation
```

Expected result: `Lifecycle tests: 3 passed, 0 failed.` The runner uses only
Android platform APIs and adds no test dependencies or fake backend to the
application APK. Instrumentation restarts the target app process; reopen
InferDroid afterward and press Run for a real NPU request.
