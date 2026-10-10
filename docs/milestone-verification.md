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

## Milestone 3 — passed, 2026-10-10

Device/model/runtime pins remain the same as milestones 1 and 2. The adapter
was rebuilt from the pinned sources to support history, sampling parameters,
incremental text callbacks, and upstream usage measurements. The Android
application and test APK were compiled with **JBR/JDK 21.0.11**; the Java
compiler toolchain is now pinned to 21, retaining source/target compatibility
17. Gradle 9.8.1, AGP 9.4.1, compile SDK 37, target SDK 36, and NDK r30-beta1
remain unchanged. Assembly, CMake/JNI, and lint passed with zero errors and
the two previously documented warnings.

The milestone 2 installation initially had GrapheneOS Network permission
denied after this update added `INTERNET`. Installed-package inspection
confirmed the denial. Enabling that permission in the owner profile allowed
localhost sockets. The app now explains this case on server start, and setup
instructions are in [local-api.md](local-api.md#grapheneos-and-client-permissions).
Other profiles were not changed.

### Real HTTP/NPU requests

The server was started through the Activity with authentication enabled at
`127.0.0.1:8080`. Host clients used a loopback ADB forward on port 18080 and
read the local key without printing it. Model discovery returned the single
Gemma model. The following completed requests used the actual service and
JNI/NPU engine, not the instrumentation fake:

| Request | Host wall time | Prefill / decode tokens | Result |
| --- | --- | --- | --- |
| First non-streaming introduction, including automatic load | 6.848 s | 15 / 44 | Same introduction as milestone 1; `finish_reason: stop` |
| Live SSE introduction, retained engine | 2.675 s | 15 / 44 | 43 non-empty text deltas; first content at 0.218 s; same completed text |
| System + user + assistant + user history, while backgrounded | 1.265 s | 42 / 6 | `Your name is Ada.`; temperature 0, top-p 0.8, seed 42 accepted |
| Eight-token completion cap, while backgrounded | 1.314 s | 15 / 8 | `Hello! I am Gemma 4,`; `finish_reason: length` |
| Eight-token request after native disconnect cancellation | 1.243 s | 15 / 8 | Successful reuse; `finish_reason: length` |

SSE used a role delta, incremental native content, a terminal finish delta,
an empty-choices usage event, and `[DONE]`. Usage was measured by LiteRT-LM;
no character-based estimate was added. Host timing includes HTTP overhead,
and these measurements are functional checks rather than controlled benchmarks.

A longer counting stream was disconnected after its first text chunk.
A concurrent completion returned **429 / engine_busy**. Native logcat showed
`SessionAdvanced::CancelProcess`; a retry was rejected until cancellation
drained, then succeeded on the same engine. Recovery including the new short
completion finished 1.593 s after disconnect. The first engine served all
five completed requests and the cancelled stream with one initialization.

Android reported `isForeground=true`, `specialUse`, and `hasBound=false`
while the Activity was backgrounded and the HTTP requests completed. Native
evidence from the real requests included:

```text
Creating LiteRT-LM NPU engine
Loading shared library: /data/app/.../lib/arm64/libLiteRtDispatch_GoogleTensor.so
Found GoogleTensorOptions
[dispatch_delegate_kernel.cc:203] Found async dispatch capabilities
SouthBound symbols resolved by 'libedgetpu_litert.so'
JNI start; backend=NPU
session_advanced.h:173] SessionAdvanced::CancelProcess
NPU engine unloaded
```

There was no CPU retry or app crash. The same known generic/contradictory
dispatch warnings appeared alongside successful NPU generation.

### Final APK and app controls

After the final Java changes, both APKs were rebuilt and installed, and all
ten instrumentation tests passed again. The Activity controls were then used
to select **port 8091**, enable **CORS**, and start the actual service. Model
discovery and browser preflight passed on that port. An authenticated real
NPU introduction completed in **5.375 s**, including loading, with 15 prefill
and 44 decode tokens.

**Stop server** closed the listener. Pressing the UI **Run** afterward reused
the same native engine and completed in **2,526 ms**. **Unload / Stop** released
the model mappings and removed foreground service status. The saved defaults
were restored to port **8080**, authentication **enabled**, and CORS **off**.
The app was left visible with the model unloaded and HTTP listener stopped.
Temporary host ADB forwards and logcat capture were removed/stopped.

Final debug APK: **15,648,103 bytes**, with exactly the same four arm64 native
library names as milestone 2, and no model weights. SHA-256:

```text
b7861e7e3a8962e9d984b1c7f4992f81f23e90efd01744b93d0295413e48f8c2
```

Adapter source SHA-256:

```text
0a349d35807bfd85f6b3c799023b6d161c6dadb6e31df409cfe0a3a3c36246b2
```

### Reproducible instrumentation

The platform-only runner reports **`Lifecycle/API tests: 10 passed, 0 failed.`**
Cases: `busyAndReuse`, `cancelAndReuse`, `stopDuringLoad`,
`splitUtf8Streaming`, `apiAuthenticationAndValidation`,
`apiChatHistoryAndErrors`, `apiLiveStreaming`,
`apiBusyDisconnectAndRecovery`, `apiCorsAndPortConflict`, and
`apiStopAndRestart`. These exercise actual loopback sockets, Android threading,
and the shared manager, using a test-only fake engine for controlled timing.
They verify auth, bounded/strict parsing, history and sampling translation,
live delivery before completion, Unicode boundaries, error shapes, UI/API
contention, request-owned cancellation, restart/rotation, and optional auth.
The real HTTP requests above separately establish NPU execution. Reproduction
commands are in the README.

Host evidence is retained temporarily in `/tmp/inferdroid-m3-real.log` and
the matching `/tmp/inferdroid-m3-*.xml` UI captures. Keys were neither printed
nor written to tracked artifacts. Third-party client apps still need individual
compatibility testing; no claim is made for Agora, FitBuddy, or RPClient.

## Milestone 4 — passed, 2026-10-10

Same Pixel 10 / GrapheneOS, Android API 37, arm64, 4096-byte pages. Independent
speech engine: **sherpa-onnx 1.13.8**, matched **ONNX Runtime 1.28.2**, multilingual
**Whisper tiny int8**, explicitly **CPU**, two runtime threads. The known-good
LiteRT-LM / Google Tensor dispatch stack was retained unchanged.

### Model and public recordings

The host preparation script downloaded the pinned
[official sherpa Whisper tiny archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2).
Its three int8 model/token files were pushed to
`/sdcard/AIModels/sherpa-onnx-whisper-tiny/`, then imported using the actual
Android folder picker. The app copied **103,609,903 bytes** into private storage
and verified all three SHA-256 values. Import and the selected public audio URI
survived app updates and the instrumentation process restart.

Recordings came from that archive's `test_wavs/0.wav`, `1.wav`, and `8k.wav`,
with its `trans.txt` as the reference. No user recordings were used. Recognition
checks were English; Italian is supported by the multilingual model and language
API but was not measured on an Italian recording.

### Real CPU transcription

These authenticated requests used the actual CPU recognizer through
`POST /v1/audio/transcriptions`. Final installed APK measurements:

| Input / options | Decoded duration | Host wall time | Result |
| --- | --- | --- | --- |
| `0.wav`, 16 kHz mono, `language=en`, verbose JSON; includes model initialization | 6.625 s | 1.409 s | HTTP 200; reference words matched, ignoring case/punctuation |
| `8k.wav`, 8 kHz mono, automatic language, verbose JSON; retained model | 4.825 s | 0.462 s | HTTP 200; language `en`; proper name Hester Prynne misrecognized |
| `1.wav`, 16 kHz mono, `language=en`, plain text, `whisper-1` alias | 16.715 s | 1.415 s | HTTP 200; recognizable transcript, including `parrot` for reference `parent` |

The alias uses the same local tiny model. Duration is measured from decoded
samples, including the 8 kHz resampling path. Timings include local HTTP and ADB
forwarding overhead; these are functional checks, not controlled benchmarks.

The same public `0.wav` was re-encoded on the host with FFmpeg to exercise real
Android decoding. All returned HTTP 200 and recognizable reference text:

| Encoding | Host wall time |
| --- | --- |
| MP3 | 2.173 s |
| M4A / AAC | 1.351 s |
| FLAC | 1.166 s |
| Ogg / Vorbis | 2.160 s |
| WebM / Opus | 3.583 s |

A **33.125-second** WAV made by repeating the public recording exercised the
25-second native chunk boundary. It completed in **4.207 s**, with the full
decoded duration reported. All chunks were processed, but the tiny model added
repeated text; this verifies long-input handling, not transcription accuracy.
The app has no overlapping segmentation, VAD, timestamps, or confidence scores.

The actual UI audio picker selected `0.wav`; **Transcribe audio** returned the
reference text in **653 ms** with the model retained. A real HTTP transcription
also completed while Home was pressed: Android reported `isForeground=true`,
`specialUse`, and **`hasBound=false`**. Native CPU initialization evidence:

```text
sherpa-onnx 1.13.8 · ONNX Runtime CPU · Whisper tiny int8 initialized
```

### Shared admission, cancellation, and NPU regression

- Concurrent actual speech requests returned **429 / engine_busy** after the
  first upload was admitted. Disconnecting an active speech request discarded
  its result; retries were rejected until the current native chunk drained.
  Recovery including a new short transcription took **1.075 s**, reusing the
  same CPU model. The gate intentionally remains occupied during that drain.
- Speech during live Gemma generation and chat during a long speech request
  both returned **429**. Disconnecting the chat stream reached native
  cancellation, then speech succeeded in **0.920 s**, including its decoding.
- With both models resident, a real NPU introduction completed in **5.695 s**
  including Gemma load. The subsequent live SSE introduction delivered **43**
  non-empty text chunks, first content at **0.250 s**, total **2.693 s**. Both
  used 15 prefill and 44 decode tokens. Speech remained usable between them.
- After the final build and instrumentation restart, real Gemma inference
  passed again in **5.166 s**, including load, with the same 15 / 44 token
  counts. Logs again showed `Found GoogleTensorOptions`, active Google Tensor
  dispatch kernels, and SouthBound symbols resolved by `libedgetpu_litert.so`.
  There was no CPU fallback; ASR CPU status remained separate from Gemma NPU.
- **Stop server** closed the listener while both models stayed loaded.
  **Unload / Stop** then logged both `Speech engine unloaded` and
  `NPU engine unloaded`, removed foreground status, and left **zero model
  mappings and descriptors**. The app was left visible with both engines
  unloaded, port 8080, authentication enabled, and CORS off. The verified speech
  bundle remains imported for the next run. Temporary ADB forwarding and
  logcat captures were removed/stopped.

### Final build and reproducible tests

JDK **21.0.11**, Gradle **9.8.1**, AGP **9.4.1**, compile SDK **37**, and pinned
NDK **r30-beta1** built the Java/JNI APK and test APK successfully. Android Studio
build and `:app:lintDebug` passed; lint reported **0 errors, 12 warnings**, including
existing theme/AppCompat, dependency/catalog, arm64-only and target-API notices.
Both APKs were installed on the reference phone.

The final platform instrumentation reports:

```text
Lifecycle/API tests: 16 passed, 0 failed.
```

The ten earlier lifecycle/chat cases passed again. Six new cases cover actual
audio decoding, multipart validation, JSON/text/verbose responses and discovery,
shared chat/speech contention, disconnect cancellation/recovery, and stopping
during speech load/import. They use real loopback sockets, Android threading,
the real Java decoder, and controlled test-only fake engines. Real native CPU
ASR and Tensor G5 inference are established separately by the requests above.
Reproduction commands and input limits are in the README and
[speech guide](speech-recognition.md).

Final debug APK: **41,120,585 bytes**; exactly seven arm64 libraries (the four
original G5 libraries and three speech libraries), five generated speech
license/provenance assets, and **no model weights**. SHA-256:

```text
13e6692a3fbbbf99f71642a29a87dd8446dbe27b11089fe13152a719a1696ee0
```

Temporary host evidence: `/tmp/inferdroid-m4-final-build.log`,
`/tmp/inferdroid-m4-final-tests.log`, `/tmp/inferdroid-m4-real.log`,
`/tmp/inferdroid-m4-backends.log`, and matching UI captures. Local API keys were
neither printed nor written to tracked artifacts. Third-party app compatibility
still needs per-client testing.

## Milestone 5 — passed, 2026-10-11

Checks ran on the same Pixel 10 / GrapheneOS over 2026-10-10–11. The
Gemma/LiteRT/G5 pins remain unchanged. Local TTS uses **sherpa-onnx 1.13.8**,
**ONNX Runtime 1.28.2**, and the official **Supertonic 3 int8 2026-05-11**
bundle on CPU with two threads. TTS has its own manager, JNI C API, private
model directory, and four retained ONNX sessions; it shares only service
ownership and the work gate with chat/ASR. It extends the existing speech JNI
library without adding another inference runtime.

The seven required model/data files total **145,295,768 bytes**. The bundle
was prepared on the host, copied to `/sdcard/AIModels/`, and imported through
the real system folder picker. The importer verified all seven checksums and
copied only those files into app-private storage. Archive/model hashes and
separate OpenRAIL-M/MIT licensing are in [tts.md](tts.md).
The runtime selects only Supertonic TTS and excludes eSpeak/piper.

### Real generated audio

The UI generated its Italian default sentence with F1 in **2,842 ms**, including
initialization. **Save WAV** used the real system document picker; the exported
file was pulled and parsed: **317,076 bytes**, **6.605 seconds**, mono 24 kHz
PCM16, with nonzero samples. The corrected **Play** control created an active
Android AudioTrack with `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`, and **24,000 Hz**.
The initial preview check caught the static-track initialization order, which
was corrected before final verification. Leaving the Activity stops preview.

The bundle's `tts.json` specifies native **44.1 kHz**, despite an upstream
example listing 24 kHz. JNI checks the actual model rate and resamples both
WAV and PCM to **24 kHz mono signed PCM16 little-endian**.

Representative final-APK API results:

| Request | Output | Audio duration | HTTP wall time |
| --- | --- | --- | --- |
| English F1, speed 1.0, first TTS request including load | WAV, 365,816 bytes | 7.620 s | 3.309 s |
| Same English text/voice, speed 1.5 | WAV, 243,892 bytes | 5.080 s | 1.670 s |
| Italian M1, speed 1.0 | WAV, 483,264 bytes | 10.067 s | 3.003 s |
| `tts-1` / `alloy` aliases | Raw PCM, 102,616 bytes | 2.138 s | 0.845 s |

Every successful response had exact Content-Length and the expected
`audio/wav` or `application/octet-stream` MIME type. WAV headers decoded to
24 kHz, mono, 16-bit samples; generated clips had nonzero amplitude.
`GET /v1/models` listed all three configured/imported models, including before
loading. Requested MP3 output returned **400 `invalid_parameter`**.

English generated speech transcribed back through the real Whisper endpoint
in **1.403 s**, reproducing the supplied words. The Italian clip transcribed
in **1.036 s** with errors. This establishes functional Italian generation and
ASR processing, without separating TTS pronunciation from Whisper tiny
recognition or claiming Italian accuracy. These are functional measurements,
not a controlled quality/performance study. All spoken texts were written for
the test; no user recordings were accessed.

### Bounds, cancellation, and runtime safety

- A real **67.306-second**, multi-chunk WAV completed in **19.801 s** during
  verification. Concurrent TTS, chat, and ASR requests each returned **429
  `engine_busy`** while synthesis owned the slot. A subsequent short request
  succeeded without reinitializing Supertonic.
- Disconnecting an active real synthesis discarded its audio and retained
  admission ownership until the synchronous call drained. Recovery took
  **6.477 s** on the final runtime, including a successful retry; 22 intervening retries received
  429. No cancelled model sessions were freed while native work was live.
- A long request at speed **0.25** exposed the export's fixed positional table:
  the predicted latent length exceeded 1000 and raised an ONNX shape exception.
  The corrected build checks predicted duration before vector estimation,
  retries a smaller text prefix, and catches C++ exceptions inside the sherpa
  shared library. This prevents exceptions crossing separate static C++
  runtimes. The exact stress request then returned **413 `request_too_large`**
  at the **120-second total-audio limit**, in **41.622 s**, without a crash.
  A subsequent normal request succeeded in **1.466 s** with the same model.
- A short utterance at speed 0.25 generated **7.944 s** of audio in **2.681 s**.
  Speed 2.0 also produced nonzero audio. Native 4.0 produced a silent short
  utterance, so the app deliberately supports **0.25–2.0** and rejects faster
  speeds before inference. Native output consisting entirely of silence is
  rejected as an engine failure rather than returned as successful speech.
- TTS text is capped at 4096 UTF-16 code units; calls use at-most-240-unit
  chunks with cancellation checks and smaller retries when required. Invalid
  Unicode, types, duplicate JSON fields, unsupported options, and missing
  models are covered by the socket-based tests. No text/audio/key is logged
  by the integration; native TTS error text is redacted.

### Regression and lifecycle

The final APK also passed the existing public Whisper WAV cases and real
Gemma introduction/live SSE. Both CPU audio engines remained resident during
NPU inference. Logs again showed the Google Tensor dispatch/delegate path and
SouthBound symbols resolved by `libedgetpu_litert.so`. A TTS request during
live Gemma generation returned 429. There was no NPU-to-CPU fallback.
The final introduction took **6.223 s** including load; warm SSE delivered
**43 content chunks**, first content at **0.420 s**, total **2.908 s**, and
the same **15 prompt / 44 completion tokens**. A following TTS request
succeeded without reloading either CPU engine.

Real TTS also succeeded while the Activity was backgrounded; Android reported
the service foreground with `hasBound=false`. TTS returned 7.620 seconds of
WAV audio in **2.745 s** while backgrounded. Speed 2.0 generated a 1.014-second
short clip, while speed 4.0 returned 400. **Stop server** retained loaded
engines. Full **Unload / Stop** drains all three workers, logs ASR/TTS/NPU
unload, removes foreground status, and releases model mappings/descriptors.
Model mappings went from **10 to 0**, and model descriptors from **8 to 0**.
The app was left visible with the listener stopped, all engines unloaded,
port 8080, authentication enabled, and CORS off. Imported audio bundles and
the saved Gemma selection remain available for the next run.

### Final build and tests

Android Studio and command-line assembly/lint passed with **JDK 21.0.11**,
Gradle **9.8.1**, AGP **9.4.1**, compile SDK **37**, and pinned NDK
**r30-beta1**. Lint: **0 errors, 12 existing warnings**. The runtime preparation
script was also checked for idempotent source output. Both APKs were installed.

```text
Lifecycle/API tests: 20 passed, 0 failed.
```

The sixteen previous cases passed again. Four TTS cases cover strict schema,
authentication, model discovery/availability, voice/language/Unicode/speed
translation, real Java decoding of generated WAV and raw PCM bytes, output-limit
errors/reuse, shared work admission, disconnect/stale cancellation ownership,
and stop during load/import. They use controlled test-only engines and real
Android threads/sockets/decoder. The actual sherpa CPU and G5 evidence comes
from the separate native requests above.

Final debug APK: **44,131,533 bytes**, seven arm64 libraries, both Supertonic
code/model notices, and **no bundled model weights**. SHA-256:

```text
7f15c9f9b2a8bcf626024f89c3c59587573c24c56204f8f5967cd6f88396b312
```

Temporary host evidence: `/tmp/inferdroid-m5-final-build.log`,
`/tmp/inferdroid-m5-final-tests.log`, `/tmp/inferdroid-m5-runtime-build.log`,
`/tmp/inferdroid-m5-api-formats.log`, `/tmp/inferdroid-m5-limit.log`,
`/tmp/inferdroid-m5-regression.log`, and `/tmp/inferdroid-m5-real.log`.
Local keys were neither printed nor written to tracked artifacts. ADB forwarding
and log capture were removed/stopped after verification. Third-party client
compatibility and the other 29 TTS languages still need individual testing.

Milestones 1–5 are complete. Milestone 6 (Gemma image understanding) has not started.
