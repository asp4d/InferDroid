# Optional thinking API verification — 2026-10-12

Optional thinking is implemented with `enable_thinking: true`, using LiteRT-LM's
native conversation setting. Reasoning and final answers are returned separately
in buffered JSON and live SSE. Direct responses remain the default.
See the [API contract and examples](local-api.md#optional-thinking).

## Build and device

The native runtime was rebuilt against the existing pins: LiteRT-LM
`0b98b80e1d846af27d10b8ab395645119cb231e4`, LiteRT
`43d8b4f20ef743a7c5beb69c365538e726cc20d9`, Bazel 7.6.1 and NDK r30-beta1.
Native adapter SHA-256:
`300f2ef679a8f540cfccb1a57dbcb3e045cdd3cf811e74b456a00e1ae37c66a3`.
Java/JNI compilation, production APK/test APK assembly and Android lint passed.
Lint reported 0 errors and 18 warnings in existing build/resource files.

The normal `dev.inferdroid` debug APK was saved at
`app/build/outputs/apk/thinking/inferdroid-debug.apk`, SHA-256
`8ef3e661d4142e1f0156d5a3af760a4bd91bc263659b01c5b8a9d7df7e32f7cf`.
It contains the seven expected arm64 libraries and no model weights.

This workstation's debug key differs from the installed app's signing key.
Replacing the existing installation would require its original keystore or a
reinstall that removes existing app data; neither was performed. Verification
used a separate **`dev.inferdroid.thinkingtest`** installation, version 0.8.0,
created with a temporary Gradle init script. The production application ID and
build configuration were not changed. The isolated APK's SHA-256 was
`c6f33bcd3528dd069cf85f8240b4431dbc3725388c45fc576198b3aab9179b5c`.
Both APKs contain the identical native runtime, SHA-256
`07df2ec509f527e3b2f2db5606ead0bfe313e381e188592fdb10c0c280284f47`.

The Pixel 10 / GrapheneOS API 37 used its existing
`gemma-4-E2B-it_Google_Tensor_G5.litertlm`, selected with the normal document
picker. Only the model's small metadata section was inspected beforehand: its
embedded template reads `enable_thinking` and inserts `<|think|>`. The pinned
Rust template renderer exposes LiteRT's extra context to those template variables.
No model file was changed or copied into the test app.

## Verification results

**All 30 instrumentation tests passed.** The five additions cover independent
UTF-8 decoding for reasoning and answers, API options/defaults, buffered/history
handling, live reasoning SSE, truncation/recovery and disconnect ownership.
The prior lifecycle, chat, speech, TTS and image protocol tests still pass. These
tests use controlled fake engines; the following checks used the real NPU model.

The [native API evidence](evidence/thinking-api-2026-10-12.json) records 14 requests:

- Default direct response: correct final answer, no reasoning field.
- Native thinking with the default 2,048-token budget: correct buffered answer
  with separate reasoning; 335 actual output tokens in 18.725 seconds.
- Live SSE: reasoning arrived before the answer; both completed strings matched
  the buffered result, with no raw thought delimiters. Total time: 18.602 seconds.
- A 16-token limit interrupted thoughts in both buffered and streamed requests:
  non-empty reasoning, empty answer, and `finish_reason: length`.
- Explicit disabling after truncation: normal completion and no reasoning.
- Echoed assistant history, with and without `reasoning_content`: identical
  answer and **63 prompt tokens** in both cases, confirming thoughts were ignored.
- Invalid thinking type and a 4,097-token cap: explicit 400 responses.
- An explicit 4,096-token cap: accepted and completed normally.
- Disconnect during live reasoning: cancellation drained; one immediate retry
  returned the expected 429, then a direct request completed in 0.901 seconds
  using the retained engine.

Logs from the test process showed `dispatch_delegate_kernel.cc`, async dispatch
capabilities, `Found GoogleTensorOptions`, SouthBound contexts and symbols resolved
by `libedgetpu_litert.so`. No CPU retry or engine reload was observed. The device's
overall thermal status was 0 after testing.

The [long-output evidence](evidence/thinking-long-output-2026-10-12.json) repeats
the ordering puzzle from the earlier experiment with native thinking and a
2,048-token cap. It generated **2,048 output tokens** in 111.982 seconds, confirming
generation beyond the old 1,024-token ceiling. Reasoning began at 0.400 seconds;
answer text began at 93.419 seconds. The response still exhausted its budget
before finishing the requested final answer and correctly reported `length`.
Increasing the budget does not guarantee completion or improve reasoning quality.

The temporary test app, instrumentation package and device UI dump were removed
after verification. Their generated private data was test-only; the original
`dev.inferdroid` installation, preferences and shared models were preserved.
No ADB forwarding remains. Saved APKs allow reproducing the isolated verification
without another native build.

## Reproduce

With an updated, debuggable `dev.inferdroid` installation and its chat server
started, run:

```bash
python3 scripts/test-thinking-api.py --output /tmp/inferdroid-thinking-api.json
```

For an isolated application ID, add `--package dev.inferdroid.thinkingtest`.
The script creates and removes its own localhost ADB forward and uses the saved
key only in memory. Output reports contain only synthetic test prompts/responses,
timings, token counts and errors. No API keys are captured.
