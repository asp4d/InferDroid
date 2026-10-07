<p align="center">
  <img src="docs/assets/inferdroid-logo.svg" alt="InferDroid logo" width="180" height="180">
</p>

# InferDroid — Tensor G5 milestone 1

A minimal **Android Studio / Java / JNI** app for the proven Pixel 10 Gemma 4
E2B NPU path. Press Run to submit `Hello, briefly introduce yourself.` and show
the response and native diagnostics. Inference runs on a worker thread. No
HTTP server or future features are implemented.

Read [the original handoff](pixel_local_ai_server_codex_handoff.md) and
[the verified native strategy](docs/native-integration.md) for exact source
APIs, hashes, storage decisions, and licensing notes.

## Open and build in Android Studio

1. Open this repository's **root directory** in Android Studio.
2. Use **JDK 17** for Gradle under Settings → Build, Execution, Deployment →
   Build Tools → Gradle. The workstation also has JDK 25; use 17 for this
   pinned Gradle/AGP combination.
3. Install Android SDK Platform **36**, Build Tools **36.0.0**, CMake **3.22.1**,
   Platform Tools, and NDK **30.0.14904198 (r30-beta1)**. Enable preview/show
   package details in SDK Manager for this exact NDK. Do not replace the pinned
   inference stack with a newer LiteRT dependency.
4. Run the native source build below once from Android Studio's Terminal.
   The upstream runtime uses Bazel; CMake builds the app's JNI bridge.
5. Sync Gradle, select the `app` run configuration and the physical Pixel 10.
   Android Studio **Build APK(s)** / **Run** builds the Java and JNI code and
   packages the prepared native runtime. Run launches the UI, but does not
   automatically submit a prompt. Press the in-app Run button when ready.

Gradle Wrapper is pinned to **8.13** with its distribution SHA-256. Android
Gradle Plugin is **8.13.0**. Application ID: **`dev.inferdroid`**. Only
**`arm64-v8a`** is packaged. Minimum Android API: 31; compile/target API: 36.

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

## Perform the first real inference when ready

Keep **Verbose native diagnostics** enabled for this first test. Start a full
capture before pressing Run so initialization, vendor, and crash messages are
preserved:

```bash
adb logcat -v threadtime > /tmp/inferdroid-first-test.log
```

In the app, leave `Hello, briefly introduce yourself.` as the prompt and tap
**Run**. Keep the app visible during this milestone's foreground test. Generated
text and diagnostics appear when the native call returns. Stop logcat with
Ctrl+C after the run, then inspect:

```bash
rg 'GoogleTensor|DispatchDelegate|SouthBound|libedgetpu_litert|InferDroid|FATAL|Fatal signal' /tmp/inferdroid-first-test.log
```

Expected evidence follows the handoff:

```text
Loading shared library: .../libLiteRtDispatch_GoogleTensor.so
Found GoogleTensorOptions
Replacing ... with delegate (DispatchDelegate)
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

No Git commit is created before the first successful physical NPU test.

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

## Verification in this session

- Native adapter and Google Tensor dispatch built from the pinned sources.
- Java, CMake/JNI, debug APK assembly, and Android lint passed. Lint's two
  warnings are intentional arm64-only targeting and availability of newer build
  tools; the required versions remain pinned.
- APK signature verified. APK inspection confirmed exactly four arm64 native
  libraries and no `.litertlm` model. Initial debug APK is approximately 15 MB.
- `adb install -r` succeeded on the connected Pixel 10. `MainActivity` launched
  successfully and its UI was inspected. Android confirmed extracted native
  libraries, arm64 ABI, and `libedgetpu_litert.so` in the app's vendor library
  access list. Device page size is 4096 bytes.
- **First app inference has not been run.** Select the existing model and press
  Run with logcat capture enabled to validate the complete app/NPU path.
