# Verified Tensor G5 integration

The complete `pixel_local_ai_server_codex_handoff.md` was read before project
changes. Its successful Pixel 10 / GrapheneOS run is the baseline.

## Exact baseline

| Component | Pinned input |
| --- | --- |
| Reference recipe | [bejoyfuuul/litert-lm-tensor-g5-npu](https://github.com/bejoyfuuul/litert-lm-tensor-g5-npu/tree/8f26b917dcd0dbe04f142f43a7c24f9141136d7f) |
| LiteRT-LM | `v0.14.0-alpha.0`, `0b98b80e1d846af27d10b8ab395645119cb231e4` |
| LiteRT / Tensor dispatch | `43d8b4f20ef743a7c5beb69c365538e726cc20d9`, from LiteRT-LM's `WORKSPACE` |
| Bazel | `7.6.1`, from `.bazelversion` |
| NDK | `30.0.14904198` / `r30-beta1`, from the reference release's `VERSIONS.txt` |
| Constraint provider | `prebuilt/android_arm64/libGemmaModelConstraintProvider.so` at the LiteRT-LM commit |
| Model | `gemma-4-E2B-it_Google_Tensor_G5.litertlm`, external to APK |

The reference README permits NDK r28b+, but its **actual published binary** was
built with r30-beta1. InferDroid uses that exact NDK rather than the older NDKs
installed on this workstation. No LiteRT Maven/AAR dependency is used.

The connected Pixel was inspected read-only on 2026-10-07. It reports Android
API 37, model size `3,113,545,589` bytes, and the following hashes:

| Existing phone file | SHA-256 |
| --- | --- |
| `litert_lm_main` | `51e74989c659192b97327808452b8f28b353c54786ca9aec877c868dd674fd61` |
| `libLiteRtDispatch_GoogleTensor.so` | `c726514b5f3f4576b354daa73091a866fb7e3da78b0574e971be3c33ea99e5f3` |
| `libGemmaModelConstraintProvider.so` | `985ec5778144730b80666a0f71f1e06038eb07dd1a3e941c6ab7f963eda00a8e` |

These match the reference release byte for byte. The newly source-built dispatch
may have a different binary hash because host/compiler build metadata differ;
its source commit and target are identical. This is recorded, not treated as an
excuse to substitute a different runtime generation.

## APIs inspected before implementation

The adapter uses the following **existing** APIs at the pinned commit:

1. [`ModelAssets::Create`](https://github.com/google-ai-edge/LiteRT-LM/blob/0b98b80e1d846af27d10b8ab395645119cb231e4/runtime/executor/executor_settings_base.h)
   accepts a path or `std::shared_ptr<ScopedFile>`.
2. [`EngineSettings::CreateDefault`](https://github.com/google-ai-edge/LiteRT-LM/blob/0b98b80e1d846af27d10b8ab395645119cb231e4/runtime/engine/engine_settings.h)
   takes those assets and `Backend::NPU`. Vision/audio backends remain unset,
   as in the working text-only CLI.
3. `GetMutableMainExecutorSettings().SetLitertDispatchLibDir(...)` is the
   actual setter in `ExecutorSettingsBase`; `SetCacheDir(...)` configures a
   writable app-private cache.
4. `EngineFactory::CreateDefault(std::move(settings))` selects the same
   `engine_impl_selected` Bazel implementation as `litert_lm_main`.
5. `SessionConfig::CreateDefault()`,
   `ConversationConfig::Builder().SetSessionConfig(...).Build(*engine)`, and
   `Conversation::Create(*engine, config)` create the conversation.
6. [`Conversation::SendMessageAsync`](https://github.com/google-ai-edge/LiteRT-LM/blob/0b98b80e1d846af27d10b8ab395645119cb231e4/runtime/conversation/conversation.h)
   plus `Engine::WaitUntilDone(Engine::kDefaultTimeout)` follows the CLI's
   async call and drain. The message is
   `{"role":"user","content":[{"type":"text","text":prompt}]}`. The
   model metadata supplies the chat template. The Java executor thread waits
   for the complete response and reads `Conversation::GetHistory()` after
   callbacks finish; the Android main thread remains free.
7. `Conversation::CancelProcess()` forwards to the pinned
   `SessionAdvanced::CancelProcess()` and its execution manager. Cancellation
   is issued from a separate Java control executor while generation waits.

The original CLI's optional sampler-flags patch is irrelevant here: the known
successful command did not override sampling. InferDroid preserves the default
session sampler and only caps generated output at 256 tokens.

`native/runtime_api.h` is **InferDroid's own C boundary**, not a fictitious
LiteRT-LM API. The Bazel-built adapter owns LiteRT-LM C++ objects. The small
CMake-built JNI library only transfers arguments/results across this boundary.
Both use the same NDK; C++ objects and allocators do not cross the C boundary.

## Native build and packaging

`scripts/build-native.sh` checks the source commit, LiteRT pin, NDK version,
Bazel version pin, and constraint-provider hash. It adds an `inferdroid` Bazel
package to the ignored checkout and builds:

```text
//inferdroid:libinferdroid_litertlm.so
@litert//litert/vendors/google_tensor/dispatch:dispatch_api_so
```

The overlay dependencies mirror `runtime/engine:litert_lm_main`. It preserves
the runtime's `LiteRt*` symbol exports needed by accelerator libraries. Android
system libraries are linked by their actual upstream names. Generated native
files go to `native/artifacts/arm64-v8a/`; Gradle packages this directory as
`lib/arm64-v8a/` in the APK. CMake adds `libinferdroid_jni.so`. AGP packages
the imported adapter automatically; a Gradle Sync task stages only dispatch
and the constraint provider into generated `jniLibs` to avoid duplicate native
entries.

The CLI's `DT_NEEDED` entries were inspected with `readelf`. The sole upstream
non-system runtime dependency for this path is the constraint provider. The
Google Tensor dispatch is dynamically discovered in the configured directory.
The standalone directory's OpenCL/WebGPU/Dawn libraries are not mandatory
`DT_NEEDED` dependencies for this text/NPU request and are not included.
Supporting CPU operations and the CPU sampler remain available in LiteRT-LM;
these are part of heterogeneous inference and do not constitute a CPU retry.

Native libraries are extracted by Android (`useLegacyPackaging=true`) into
`ApplicationInfo.nativeLibraryDir`. That exact directory is passed to LiteRT.
Neither executable code nor dispatch is loaded from shared storage.

On this device, `/vendor/etc/public.libraries.txt` contains
`libedgetpu_litert.so`. The manifest declares it with `<uses-native-library>`
as required by [Android 12+ vendor library access](https://developer.android.com/guide/topics/manifest/uses-native-library-element).
The pinned dispatch uses the **bare soname** to resolve it through Android's
linker namespace, rather than forcing an inaccessible absolute vendor path.
See [`sb_api_late_binding.cc`](https://github.com/google-ai-edge/LiteRT/blob/43d8b4f20ef743a7c5beb69c365538e726cc20d9/litert/vendors/google_tensor/dispatch/sb_api_late_binding.cc).

## Storage and runtime evidence

The Java document picker uses `ACTION_OPEN_DOCUMENT` and keeps read permission.
The returned descriptor must be a seekable, non-empty local file. Native code
duplicates it with `F_DUPFD_CLOEXEC` and gives ownership of the duplicate to
upstream `ScopedFile`; Java closes its original when loading finishes. The
loaded native engine retains its model assets and descriptor until unload.
Explicit dispatch/cache directories remove the CLI's dependence on the model
file's parent directory. This avoids a second 3 GB model copy and requires no
broad storage permission. A raw absolute path is also supported if app-readable.

There is no backend selection fallback loop. Any upstream initialization or
generation error reaches Java diagnostics. A successful generation additionally
requires observing both the configured Google Tensor dispatch and
`libedgetpu_litert.so` in `dl_iterate_phdr` while the engine is alive. Loaded
libraries are evidence of initialization, **not by themselves proof of NPU graph
execution**; confirm the delegate/SouthBound lines for the first real test.

The pinned LiteRT logger writes to Android logcat and stderr. The adapter
captures stdout/stderr during each serialized request, bounds retained logs to
128 KiB, and returns them with benchmarks to Java. Native logcat remains
available after an upstream abort. Verbose diagnostics are enabled initially
for the first test and can be disabled in the UI. InferDroid does not print full
prompts or generated text to its own logcat messages.

## Licensing and source limitations

LiteRT, LiteRT-LM, and the reference scripts are Apache-2.0. The adapter's source
adaptation is attributed in the APK notice. The dispatch implementation is
public source and is built locally. Upstream root licenses and the source
build's dependency notices are packaged under `assets/third_party/`.

The **constraint provider is an upstream binary-only dependency at this tag**:
its real implementation is not supplied as C++ source in the checkout. Its
header/BUILD and the repository license identify Apache-2.0, and the reference
release redistributes the identical LFS artifact. InferDroid obtains exactly
that artifact, verifies its hash, and keeps it outside tracked source. Rebuilding
everything, including this provider, requires its unavailable implementation;
substituting the CMake stub would change the proven runtime and is not done.
Collected source licenses do not establish the license inventory of any
undisclosed internals of this binary; resolve that inventory before public
binary redistribution if required. No third-party runtime binaries are committed.

The model's [upstream card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
currently identifies Apache-2.0. Weights are downloaded separately, never put in
the APK. The proprietary vendor runtime and Tensor SDK compiler are not copied
or redistributed. No compiler/SDK beta access is needed for this already
AOT-compiled model and the public dispatch source.

## Service and native lifecycle

`InferenceEngine`, `GenerationRequest`, `GenerationResult`, and `EngineManager`
keep the Java application independent of JNI, HTTP schemas, and future tools.
`InferenceService` owns the manager and its serialized worker. The Activity
binds while visible and releases its listener on stop. Loading/generation starts
the foreground service with a notification before native work begins. The model
remains resident across requests and Activity recreation/backgrounding; a fresh
conversation is created and discarded for every request.

The custom C boundary exposes load/generate/cancel/unload. Opaque numeric IDs
refer to native engines through a synchronized registry of shared owners;
Java never receives a C++ pointer. Cancellation includes a request ID so a
delayed cancel cannot affect the next request. The native conversation remains
alive until `Engine::WaitUntilDone` drains its tasks and the terminal callback
arrives. As in upstream `Conversation::SendMessage`, the terminal notification
is awaited before reading the completed history or releasing the conversation.
Unload drains the Java cancellation executor too, then destroys the engine off
the main thread. JNI loading failures release any newly allocated native engine.

The service is private (`exported=false`), uses `specialUse` on Android 14+,
and requests only foreground-service, notification, and work-time wake-lock
permissions. The wake lock is released between operations. `START_NOT_STICKY`
means a process kill does not silently reinitialize the large model. There is
no HTTP server, ASR/TTS, vision, image generation, or ToolManager implementation.

Two milestone 1 APK runs and six milestone 2 service requests passed on
2026-10-08, returning generated text to Java with matching dispatch-kernel and
vendor-library evidence. Cancellation, background execution, native
descriptor/mapping release, and reload were also verified on the Pixel. See
[the verification record](milestone-verification.md). The original standalone
success remains the baseline; these results verify that route inside the APK.
