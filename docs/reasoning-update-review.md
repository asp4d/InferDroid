# Review of the reasoning update — 2026-10-12

Scope: every file added or changed by commit `17abc31`, pulled over `79b51d0`.
The handoff describes the original local Java/JNI/NPU app; the new documentation,
code and evidence establish the intent of this update. The original conversation
that requested the update is not stored in this repository.

The update first measured reasoning using a system-prompt marker on an older
0.1.0 installation, then added an optional native `enable_thinking` setting.
The resulting API keeps direct responses as the default, separates thoughts from
answers in JSON and SSE, ignores historical thoughts during prefill, and expands
the shared output budget to 4,096 tokens. The UI still requests direct responses.
Its earlier implementation checks used a separate 0.8.0 test installation.

## Findings and corrections

### Incompatible cached native library

The checkout's ignored native artifacts had not been rebuilt after the pull.
`VERSIONS.txt` recorded adapter SHA-256
`22cc2596920c755a323f8fa09d9b03f4a1e4b6c4e943dc84ef82ec79f15ee2a6`,
which exactly matches the adapter in `79b51d0`. The new source hashes to
`300f2ef679a8f540cfccb1a57dbcb3e045cdd3cf811e74b456a00e1ae37c66a3`.

The pull changes both the C callback signature and the native result structure.
Gradle previously checked that the libraries existed but could package this old
library with the new JNI bridge. Linking cannot detect those C ABI differences;
generation would cross an incompatible interface. The unsafe combination was
rejected before installing the corrected APK; a crash with that old binary was
not deliberately reproduced.

The pinned native runtime has now been rebuilt. `verifyNativeRuntime` runs before
staging the APK libraries and checks fingerprints of the adapter, C API header
and Bazel target, plus the three library checksums. It rejects missing metadata,
stale source fingerprints and mismatched libraries with rebuild instructions.
The build script records the copied inputs that were actually compiled.

### Verification could accept an incorrect answer

`test-thinking-api.py` previously checked whether `391` appeared anywhere in
the response. It incorrectly accepted `ANSWER: 3910`, `ANSWER: 1391`, and
`391 is not the answer` in a desktop reproduction. It now checks the numerical
final answer and requires a normal completion. Four regression tests cover
correct replies, wrong numbers, intermediate work, empty replies and truncation.
Its device report now reads the actual device model rather than hardcoding it.

The README's instrumentation instructions also incorrectly expected 20 tests;
the runner has 30. The instructions now reflect that count and explain the
native rebuild requirement.

## File-by-file review

Paths are relative to the project root. Historical evidence was checked for
internal consistency; those checks are distinct from rerunning inference.

| File in the pull | Review result |
| --- | --- |
| `README.md` | Reasoning defaults and historical results agree with the implementation and evidence. Corrected the test count and documented the build check. |
| `app/src/androidTest/java/dev/inferdroid/engine/EngineManagerInstrumentation.java` | Reviewed all new dispatch entries and the independent UTF-8 channel test. All 30 registered tests passed on the reconnected Pixel. |
| `app/src/androidTest/java/dev/inferdroid/server/OpenAiServerTests.java` | Reviewed options, history stripping, live SSE, truncation and disconnect tests, including the fake engine changes. The device suite passed; actual NPU checks also passed separately. |
| `app/src/main/cpp/native_inference.cpp` | Reviewed the new reasoning callback, JNI constructor descriptor, byte transfer and callback lifetime. Identified the requirement for the matching native ABI; JNI builds successfully. |
| `app/src/main/java/dev/inferdroid/engine/GenerationListener.java` | The default reasoning callback preserves existing listener compatibility. |
| `app/src/main/java/dev/inferdroid/engine/GenerationRequest.java` | Existing constructors preserve direct mode; the new constructor carries a per-request thinking flag. |
| `app/src/main/java/dev/inferdroid/engine/GenerationResult.java` | Checked old constructors, UTF-8 decoding, new reasoning storage and preservation through `withDiagnostics`. |
| `app/src/main/java/dev/inferdroid/engine/InferenceEngine.java` | The fallback listener emits reasoning and answer separately on successful generation. |
| `app/src/main/java/dev/inferdroid/engine/NativeInference.java` | Checked payload serialization and separate UTF-8 decoder state, including incomplete byte sequences and final flush. The Java code compiles. |
| `app/src/main/java/dev/inferdroid/server/OpenAiProtocol.java` | Checked defaults, Boolean validation, output limits, history validation/stripping and buffered response fields against the documented contract. |
| `app/src/main/java/dev/inferdroid/server/OpenAiServer.java` | Checked channel routing, shared queue bounds, final stream comparison, disconnect cancellation and retained-engine ownership. |
| `docs/evidence/reasoning-2026-10-12.json` | Parsed every trial and checked scoring, token totals, caps and aggregate results across its 26 historical requests. |
| `docs/evidence/thinking-api-2026-10-12.json` | Parsed all 14 historical requests; verified buffered/SSE equality, history token equality, truncation, error statuses and recovery. |
| `docs/evidence/thinking-long-output-2026-10-12.json` | Checked the 2,048-token truncated result and separate reasoning/answer timings. It demonstrates output beyond 1,024 tokens, not a completed answer. |
| `docs/local-api.md` | Defaults, budget semantics, history rules and channel fields match the code. |
| `docs/native-integration.md` | Checked the pinned API calls, conversation settings, channel extraction and JNI description against local upstream source and implementation. |
| `docs/reasoning-verification.md` | Checked the older-installation distinction, scoring, timings and totals against evidence. Independently enumerated the ordering puzzle: only `ACDB` is valid. |
| `docs/thinking-api-verification.md` | Clearly identifies the separate test installation and historical artifact hashes. Its earlier success does not validate this checkout's previously stale native artifacts. |
| `gradle/gradle-daemon-jvm.properties` | The installed Gradle wrapper successfully selects Java 21 / JetBrains on this Linux workstation. Other operating systems were not tested. |
| `native/runtime_adapter.cc` | Reviewed thinking configuration, explicit thought channel, token limits, streaming callbacks, final extraction, metrics and cancellation lifetime. Rebuilt with the existing pinned runtime. |
| `native/runtime_api.h` | Reviewed the appended reasoning fields and changed callback signature; these require rebuilding the runtime and JNI together. |
| `scripts/sign-thinking-apk.sh` | Reviewed the committed version and checked Bash syntax. Its existing staged deletion was preserved. Signing was not executed. |
| `scripts/test-device-reasoning.py` | Reviewed request modes, SSE parsing, scoring, report persistence, authentication handling and ADB cleanup. Syntax and CLI checks pass; a fresh paired arithmetic probe returned correct, complete answers in both modes. |
| `scripts/test-thinking-api.py` | Reviewed every scenario and fixed the false-positive answer check and device metadata. Syntax, CLI, four regression tests and all 14 real NPU API requests pass. |

All 41 historical evidence records have valid JSON, consistent available token
totals and no API-key or authorization fields. Historical records were preserved.

## Fresh validation completed

- The pinned Bazel native build completed and all three runtime checksums passed.
- The new build check rejected the actual pre-pull adapter. Further checks rejected
  stale adapter/API/build fingerprints, an incorrect library checksum and missing
  metadata, then accepted the restored matching runtime.
- `./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`
  passed. Android lint reported **0 errors and 13 warnings**.
- The IDE build also passed, with existing Gradle configuration warnings.
- `python3 -m unittest discover -s scripts/tests -v` passed all four tests.
  Both probe scripts compile and their `--help` commands succeed; Bash syntax
  checks and `git diff --check` also pass.
- The debug APK has seven arm64 libraries and no model weights. The merged
  inference library exactly matches the rebuilt artifact, and APK libraries
  match Gradle's outputs after symbol stripping. APK signature verification
  and 16 KiB ZIP alignment checks pass.

Built APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `321751f05c5ec007cd524f9e666f1a71354b174fb9024e5333b3674cdec50e6d`.

## Device validation completed after reconnection

The Pixel 10 became available after the USB reconnection. The corrected normal
`dev.inferdroid` APK and test APK installed successfully on Android API 37 /
GrapheneOS. The app was uninstalled in the active Android user (0), although
its package was still installed in other profiles; no app data was cleared.
The installed APK's SHA-256 matches the artifact recorded above. The app's
Network and notification permissions were enabled for the tests.

The [device summary](evidence/reasoning-review-device-summary-2026-10-12.json)
records all 30 passed instrumentation tests and **38 fresh HTTP records**,
including initial checks that exposed prompt-dependent recognition omissions.
These are engineering regression checks with synthetic images/text and public
speech recordings, not a general model-quality benchmark.

| Real device check | Result |
| --- | --- |
| App model selection and direct UI generation | Existing 3.11 GB model selected through SAF without copying; initial generation completed in 2.367 seconds. |
| Google Tensor execution | Logs show `dispatch_delegate_kernel`, Google Tensor dispatch and SouthBound resolution through `libedgetpu_litert.so`; no fatal runtime errors observed. |
| Native reasoning API | [All 14 requests passed](evidence/reasoning-review-native-api-2026-10-12.json): defaults, buffered/SSE equality, separate channels, history stripping, caps, invalid fields, disconnect cancellation and retained-engine recovery. Reasoning began at 0.343 seconds; the arithmetic answer began at 18.077 seconds. |
| PNG/JPEG/WebP and thinking with an image | [All four explicit description/transcription requests succeeded](evidence/reasoning-review-multimodal-retry-2026-10-12.json), reading `HELLO 42`; image reasoning was returned separately. |
| Multiple images and invalid animation | [Explicit shape/color request](evidence/reasoning-review-audio-and-images-2026-10-12.json) identified blue square then red circle. Animated WebP was rejected with HTTP 400. |
| Local TTS | Italian F1 WAV and English M1 PCM completed in 3.240 and 1.759 seconds. Both were non-silent mono PCM16 at 24 kHz; durations were 4.648 and 3.641 seconds. |
| Local ASR | Public WAV, 8 kHz WAV and MP3 recordings transcribed successfully; verbose responses included duration/language. Chat remained usable afterward. |
| Expanded output and background service | [A live background stream generated 1,280 tokens](evidence/reasoning-review-long-output-2026-10-12.json), completed SSE, correctly reported `length`, and recovered with a successful direct request. It took 126.336 seconds; output caps do not imply fixed latency. |
| Standalone probe CLI | [Plain and native-thinking arithmetic cases](evidence/reasoning-review-probe-cli-2026-10-12.json) returned `391` with normal completion, taking 6.026 / 109 tokens and 12.406 / 225 tokens. |
| Activity recreation, cold restart and unload | Model selection and persisted URI access survived restart; cold-start UI generation completed in 4.078 seconds. Unload released all 14 Gemma mappings and the app's work wake locks. |

The initial [terse OCR request](evidence/reasoning-review-multimodal-2026-10-12.json)
returned only `42`. The first multiple-image request returned colors without
shape names. More explicit prompts recovered the complete text and both shapes;
the initial incomplete checks are preserved rather than reported as passed.
Whisper tiny also rendered “Hester Prynne” as “herprin” in the public 8 kHz
recording. These observed accuracy limits remain; no model weights were changed.
No application/protocol failures were reproduced after rebuilding the native
runtime.

The IDE debugger launch still reports `Cannot find runner for app`. Actual
device logs, UI state, API responses and process mappings supplied runtime
evidence instead; no debugger attachment is claimed.

The app is left **installed and configured**, with all engines unloaded and the
test listener stopped. Imported speech/TTS bundles and the Gemma selection are
ready for use. Temporary ADB forwards and the device UI dump were removed.
The generated API key was checked against all new report files and is absent
from them. Existing local changes to `scripts/sign-thinking-apk.sh` and
`app/release/` were left untouched.

## Agora follow-up

The later Agora report exposed an existing nonempty-tools rejection that also
predates the reasoning pull. [Agora compatibility](agora-compatibility.md)
documents the subsequent client-function bridge, reasoning defaults and aliases,
tool-history/result association, native truncation fix, and the final verification.
The updated normal installation passed 34 instrumentation tests, 17 native API
checks and four image/audio/reuse regressions. The final listener is running
with authentication and the thinking default enabled for Agora; this supersedes
the stopped-listener state recorded at the end of the initial review above.
