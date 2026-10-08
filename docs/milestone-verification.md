# Physical milestone verification

## Milestone 1 — passed, 2026-10-08

Device: Pixel 10 (12 GB), GrapheneOS, arm64, Android API 37, 4096-byte pages.
Model: `gemma-4-E2B-it_Google_Tensor_G5.litertlm` (3,113,545,589 bytes).
Storage: persisted Files document URI; no model copy or APK-bundled weights.
Prompt: `Hello, briefly introduce yourself.`

The app's Run button was pressed twice. Both runs displayed:

> Hello! I am Gemma 4, a Large Language Model developed by Google DeepMind. I am an open weights model designed to understand and generate human-like text based on the information I have been trained on.

| Measurement | First run | Repeat |
| --- | --- | --- |
| Java request wall time | 5,674 ms | 3,997 ms |
| Engine initialization | 2,612 ms | 1,374 ms |
| Time to first token | 0.27 s | 0.12 s |
| Prefill tokens | 15 | 15 |
| Decode tokens | 44 | 44 |
| Decode throughput | 17.10 tokens/s | 18.88 tokens/s |

Actual native evidence (installation paths shortened):

```text
MainExecutorSettings: backend: NPU
Loading shared library: /data/app/.../lib/arm64/libLiteRtDispatch_GoogleTensor.so
Found GoogleTensorOptions
[dispatch_delegate_kernel.cc:203] Found async dispatch capabilities
llm_litert_npu_compiled_model_executor.cc:477] LatencyStats:
SouthBound symbols resolved by 'libedgetpu_litert.so'
Loaded EdgeTPU southbound: /vendor/lib64/libedgetpu_litert.so
```

This APK emits dispatch kernel evidence rather than the standalone test's
literal `Replacing ... (DispatchDelegate)` line. It also emitted the known
generic accelerator-registration and contradictory buffer-requirement
warnings described in the handoff. Both runs completed successfully without
a CPU retry. The generated text, NPU executor timings, active dispatch kernels,
and loaded vendor path together establish the working app/NPU route.

Runtime inputs remained pinned to LiteRT-LM `0b98b80...`, LiteRT `43d8b4f...`,
Bazel 7.6.1, and NDK r30-beta1. The current Android Studio project built and
linted successfully with Gradle 9.8.1, AGP 9.4.1, and compile SDK 37. Lint:
0 errors, 2 warnings (arm64-only ChromeOS ABI and intentionally retained target
API 36).

Full host captures for this session are in `/tmp/inferdroid-first-test.log`,
`/tmp/inferdroid-m1-diagnostics.txt`, and `/tmp/inferdroid-m1-result.png`.
They are temporary local evidence; this document retains the relevant results.

## Milestone 2 — passed, 2026-10-08

The service APK was installed on the same Pixel, force-stopped, and restarted
before the tests. Notification permission was granted through the Android
dialog. Every completed request used the same model and default introduction
prompt as milestone 1, and returned the introduction quoted above to Java.

| Request | Java wall time | TTFT | Decode throughput |
| --- | --- | --- | --- |
| First request after explicit Load model | 2,435 ms | 0.13 s | 18.86 tokens/s |
| Second request, retained engine | 2,414 ms | 0.13 s | 19.69 tokens/s |
| Activity backgrounded until completion | 3,570 ms | 0.15 s | 12.93 tokens/s |
| Retained engine after cancellation | 2,535 ms | 0.13 s | 18.59 tokens/s |
| Run automatically reloads after unload | 4,738 ms | 0.12 s | 18.82 tokens/s |
| Final Run after notification stop | 4,802 ms | 0.12 s | 18.72 tokens/s |

Each completed request processed 15 prefill tokens and 44 decode tokens.
The initial explicit load took 2,133 ms. That engine served the first four
completed requests and a cancelled request with **one initialization**.
The last two wall times include a new load (2,252 ms and 2,316 ms respectively).
Upstream request benchmark output repeats the retained engine's original
initialization measurements; those fields are not additional model loads.
These are functional test measurements, not a controlled performance study.

Verified lifecycle cases:

- **Load without generation:** status reported NPU initialized / first
  generation pending. Android confirmed a foreground `specialUse` service and
  an ongoing notification. The model was accessed through its document
  descriptor without a copy.
- **Reuse:** native logcat recorded one `Creating LiteRT-LM NPU engine` for the
  initial request sequence. Later requests reported `Reusing loaded NPU engine;
  fresh conversation for this request.`
- **Background:** a request completed after Home was pressed, while the
  Activity was unbound (`hasBound=false`) and the service remained
  `isForeground=true`. Reopening the Activity displayed the completed result.
- **Cancel and recover:** Run followed by Cancel reached upstream
  `SessionAdvanced::CancelProcess`. The UI reported `Generation cancelled.
  Model remains loaded.` The next request succeeded without reinitialization.
- **Stop during load:** Run from unloaded followed immediately by Unload /
  Stop allowed initialization to drain, skipped the queued generation, and
  unloaded the engine. No JNI generation start occurred for that request.
- **Stop during generation:** Unload / Stop drained active work and destroyed
  the engine. Status returned to unloaded with no model memory mappings and
  no foreground service.
- **Notification action:** Unload / Stop from the expanded ongoing notification
  stopped the background service. After cleanup, `/proc/<app-pid>/fd` and
  `/proc/<app-pid>/maps` contained **zero model descriptors and mappings**.
- **Wake lock:** Android's current `Wake Locks` list was empty after a request
  completed, while the engine was still loaded. Historical wake-lock records
  are separate from this live list.
- **Reload:** Run initialized a new engine and generated successfully after
  unloading, including after stopping through the notification.

Native evidence from the service test:

```text
MainExecutorSettings: backend: NPU
Loading shared library: /data/app/.../lib/arm64/libLiteRtDispatch_GoogleTensor.so
[dispatch_delegate_kernel.cc:203] Found async dispatch capabilities
SouthBound symbols resolved by 'libedgetpu_litert.so'
Loaded EdgeTPU southbound: /vendor/lib64/libedgetpu_litert.so
session_advanced.h:173] SessionAdvanced::CancelProcess
llm_litert_npu_compiled_model_executor.cc:477] LatencyStats:
NPU engine unloaded
```

NPU executor latency statistics were emitted when the retained engine was
destroyed. The app never retried on CPU, and no app crash was observed.
Initialization/delegate/vendor evidence and successful generated text verify
the same Tensor G5 route as milestone 1.

Three platform-SDK instrumentation tests also passed on this Pixel:
`busyAndReuse`, `cancelAndReuse`, and `stopDuringLoad`. They exercise actual
Android main/worker threading with a fake engine to control timing. The fake
exists only in the test APK; it does not establish NPU execution. The six
physical UI requests above provide that separate evidence. Reproduction
commands are in the README.

The tested debug APK contains exactly four arm64 libraries and no model:
`libinferdroid_jni.so`, `libinferdroid_litertlm.so`,
`libLiteRtDispatch_GoogleTensor.so`, and `libGemmaModelConstraintProvider.so`.
Size: 25,886,940 bytes. SHA-256:
`5588ff37b710d0b5f0da2ca4f831a0f46095be47a4c958ac7d8344e5ec11095d`.
Runtime pins remained unchanged; the source adapter SHA-256 is
`e3c27526285e0b2b7b53ed31b6ddcd61bb7f385355d1e6f7f23f7c581c91cd7d`.
Java/JNI assembly and lint passed with the current Android Studio build tools
(0 lint errors, the same two documented warnings).

Host evidence is retained temporarily in `/tmp/inferdroid-m2-real-test.log`,
`/tmp/inferdroid-m2-*-ui.xml`, `/tmp/inferdroid-m2-final-result.png`, and the
matching service/descriptor/mapping captures. Logcat capture was stopped after
verification. The installed app was left visible with the final generated
introduction and its verified NPU model loaded.

Milestones 1 and 2 are complete. Milestone 3 (HTTP/OpenAI server) has not started.
