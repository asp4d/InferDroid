# Agora compatibility — 2026-10-12

The reported `Invalid_parameter [invalid_request_error]: Tool calling is future
work.` error was reproduced against the normal InferDroid installation. A
request containing valid, nonempty `tools` returned 400 before native inference.
This rejection also exists in the parent of the reasoning pull (`79b51d0`);
it was an existing unsupported feature exposed by Agora's request.

The connected Pixel has Agora **2.1.0**, version code **31**. Its
[generation layer](https://github.com/newo-ether/Agora/blob/v2.1.0/app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt)
supplies enabled client tools independently of thinking. Its
[custom OpenAI adapter](https://github.com/newo-ether/Agora/blob/v2.1.0/app/src/main/java/com/newoether/agora/api/openai/CustomOpenAiProvider.kt)
only adds a reasoning control for Qwen 3.8 model IDs; it leaves the Gemma
request unchanged. The
[base provider](https://github.com/newo-ether/Agora/blob/v2.1.0/app/src/main/java/com/newoether/agora/api/openai/BaseOpenAiProvider.kt)
serializes tool definitions and recognizes `reasoning_content` in replies.

## Configuration

1. In InferDroid, stop the server and enable **Use thinking when the client
   omits reasoning controls**, then start the server again. The checkbox starts
   disabled on new installations; it was enabled on this Pixel for Agora testing.
2. In Agora, use the existing custom OpenAI provider with
   `http://127.0.0.1:8080/v1`, InferDroid's API key, and model
   `gemma-4-E2B-it_Google_Tensor_G5`. Keep **Responses API** disabled.
3. Leave the total output cap within **1–4096** tokens. Thinking and the answer
   share this budget. A small client cap can finish inside a thought block.
4. Agora's thinking toggle can display native thoughts, but its custom adapter
   does not send this model a per-request switch. Change InferDroid's default
   to disable thinking for such requests. Clients that send `enable_thinking`,
   `reasoning_effort`, or `reasoning.enabled`/`reasoning.effort` override the default.

Gemma has a binary thinking switch. Positive effort levels all enable that
switch; they do not select distinct reasoning strengths. Separate thinking
budgets and `/v1/responses` are unsupported and return explicit errors.

## Implementation

InferDroid now supports OpenAI function declarations, `tool_choice: "auto"`
and `"none"`, assistant `tool_calls`, and linked `role: "tool"` results. The
Java engine types carry functions independently of HTTP; JNI passes definitions
through the pinned LiteRT-LM `JsonPreface`. The native Gemma processor parses
function output. Complete calls receive IDs and are returned to the client in
buffered messages or indexed SSE deltas with `finish_reason: "tool_calls"`.
InferDroid executes no tools and makes no outbound requests.

The protocol validates unique call IDs and complete result batches before
inference. Since the embedded Gemma template identifies results by function
name and position, JNI serialization reorders a complete batch to its original
call order. This preserves association when two calls use the same function
name and their results arrive in reverse order. Assistant thoughts are stripped
from history as before.

The template's parameter formatter omits actual fields named `description`,
`type`, `properties`, `required` and `nullable`, including nested fields used by
Agora's memory tools. Affected field schemas are additionally supplied in the
function description, preserving the original model template and backend.

Function arguments are withheld until the decode completes and the function
name and argument object are validated. Length-limited decodes never return
executable calls. A native regression also caught unfinished function syntax
appearing in `content`; the adapter now strips that suffix consistently from
both live deltas and completed text. Malformed parsed function output fails
explicitly. The updated C interface and JNI bridge were rebuilt together and
checked by the native artifact fingerprint guard.

Basic typed object/array schemas, enums and nullable types are supported.
Schema references/combinators, `strict: true`, and forced/named tool choices
are rejected. Clients must validate generated arguments before execution.
See the [full API contract](local-api.md#client-executed-functions).

## Verification and limits

The normal `dev.inferdroid` installation was updated without clearing app data
or changing Agora's endpoint, credentials or existing conversations. Native
libraries were rebuilt with the same pinned Tensor G5 runtime. Gradle and the
IDE build passed; lint has zero errors and the 13 existing warnings.

All **34 instrumentation tests** pass, covering the earlier lifecycle,
streaming, image, ASR/TTS and thinking checks plus function histories, invalid
requests, complete SSE tool delivery, truncation/recovery and reasoning controls.
They use fake engines and real Android transports/decoders; the separate
[native API evidence](evidence/agora-api-verified-2026-10-12.json) records all
**17 checks passing**, including 13 successful real NPU requests and four
expected validation failures. A separate
[cross-engine regression](evidence/agora-multimodal-regressions-2026-10-12.json)
passed image transcription (`HELLO 42`), 24 kHz mono PCM16 WAV synthesis,
Whisper file transcription and subsequent NPU reuse. The reproducible command is:

```bash
python3 scripts/test-agora-api.py --default-thinking --output /tmp/agora-native-check.json
```

Its cases include identical buffered/streamed function arguments and thoughts,
local addition execution and returned results, reversed parallel histories,
opaque same-function results linked by ID, schema metadata field names,
reasoning compatibility controls, incomplete functions in buffered/SSE output,
and recovery. Only the synthetic addition function is executed by the script.
No web, shell, MCP, memory-write or other external tool is executed during these
checks.

The actual Agora UI was tested with separate synthetic chats. A verified clean
prompt, `Compute 17 multiplied by 23. Reply only with the result. Do not use
tools.`, returned `391` and displayed a separate native thinking block on the
final build (22 seconds in the last run). See the
[synthetic UI screenshot](evidence/agora-ui-2026-10-12.png) and
[installed-build identity and final state](evidence/agora-device-summary-2026-10-12.json).
An
earlier fast ADB input had mangled spacing and returned `51`; a clean retry
succeeded. This is evidence of transport/client compatibility, not a guarantee
of model answer accuracy. The existing eight-question benchmark already records
thinking accuracy and truncation limits.

Earlier [partial format-check evidence](evidence/agora-api-2026-10-12.json) and
[unfinished-function evidence](evidence/agora-api-truncation-2026-10-12.json) are
retained. The parallel addition response was numerically correct (`3 + 40 = 43`)
but omitted the requested `ANSWER:` prefix; its numerical oracle now also
accepts an exact, correct complete equation. The unfinished function suffix
was a native adapter defect and was corrected before final verification.

The server is left running with authentication enabled and its thinking default
enabled for Agora. The model remains loaded and idle; no test ADB forwarding is
left behind. Source changes are uncommitted. Existing user changes to
`scripts/sign-thinking-apk.sh` and `app/release/` were preserved.
