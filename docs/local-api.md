# Local OpenAI API — milestones 3–6

`InferenceService` owns an optional `OpenAiServer` listening **only on
127.0.0.1**, initially port **8080**. The server has no LAN bind setting and
makes no outbound requests. Java's `ChatGateway` routes protocol-neutral
`GenerationRequest` objects to the same `EngineManager` used by the UI.
`TranscriptionGateway` independently routes audio requests to `SpeechManager`.
`SynthesisGateway` routes text-to-speech requests to `TtsManager`.
All three lifecycles share `WorkGate`. No OpenAI account, cloud service, or API
subscription is involved.

## Start and configure

1. Select the existing Tensor G5 model with **Choose model** for chat, or
   [import the separate Whisper bundle](speech-recognition.md) for ASR or
   [Supertonic bundle](tts.md) for TTS.
2. Scroll to **Local OpenAI API**. Keep **Require local API key** enabled;
   **Copy key** copies the generated key for use in your client. The app masks
   the key and marks the clipboard entry sensitive on Android 13+.
3. Leave port **8080**, or enter another port from 1 through 65535. Enable
   **Allow browser / WebView clients (CORS)** if your client needs it.
4. Press **Start server**. The displayed URL is the client's base URL.
   Starting the listener does not load models; the first request loads its
   engine automatically. **Load model** / **Load speech** / **Load TTS** can initialize them
   beforehand. An ASR/TTS-only listener does not need a Gemma selection.
5. The service and notification remain active while the server is listening,
   even before a model is loaded or after an initialization failure. Leaving
   the Activity does not stop the listener.

| Client setting | Value |
| --- | --- |
| OpenAI base URL | `http://127.0.0.1:8080/v1` |
| API key | The key copied from InferDroid |
| Chat model ID | `gemma-4-E2B-it_Google_Tensor_G5` |
| ASR model ID | `sherpa-onnx-whisper-tiny` |
| TTS model ID | `sherpa-onnx-supertonic-3-int8` |

Port, key, authentication choice, CORS choice, and the default thinking mode are saved privately. Stop
the listener before changing them or choosing a different model. **Generate
new key** replaces the saved key; clients must use it after the next start.
Authentication can be explicitly disabled for clients that require it, but
localhost is accessible to other apps in the same Android profile.

**Stop server** closes the listener and its client connections, cancels its
active request, and leaves loaded models available to the UI. **Unload /
Stop**, including the notification action, closes the listener, drains native
work, unloads all three engines, and stops the service. Initialization remains
non-interruptible; cancellation during loading skips generation after loading
returns. A process kill does not automatically restart the server or reload
the model. Start it again from the app.

## GrapheneOS and client permissions

InferDroid now declares Android's `INTERNET` permission because sockets need
it even when bound to loopback. GrapheneOS's **Network** toggle also guards
localhost. If an update adds this permission to the milestone 2 installation,
check **Settings → Apps → InferDroid → Permissions → Network → Allow**. The
app reports a disabled permission when starting the server. This upgrade case
was observed on the reference Pixel. See the
[GrapheneOS Network permission documentation](https://grapheneos.org/features#network-permission-toggle).

Client apps need their own Network permission. Some clients also disallow
cleartext HTTP through their network-security policy. Configuring InferDroid
cannot override a client's policy; test each client separately. See
[Agora configuration and compatibility](agora-compatibility.md) and
[FitBuddy setup and compatibility](fitbuddy-compatibility.md). RPClient is reported
working by the user; its UI has not been independently rechecked here.

CORS is disabled initially. Enabling it permits origins with
`Access-Control-Allow-Origin: *`, `GET, POST, OPTIONS`, and the Authorization
and Content-Type headers. Preflight OPTIONS requires no bearer token; actual
GET/POST requests require the key when authentication is enabled. Cookies and
credentialed CORS are not used. The server rejects non-local Host names to
limit DNS rebinding. CORS does not replace authentication.

## API contract

The wire format follows the official
[Chat Completions schema](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
and [streamed chunks](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events).
Chat implements the following text/image subset; independent audio contracts and
examples are in [speech-recognition.md](speech-recognition.md) and [tts.md](tts.md).

| Endpoint | Behavior |
| --- | --- |
| `GET /v1/models` | OpenAI-style `list` with configured Gemma and imported Whisper/Supertonic models, including while unloaded; unconfigured engines are omitted. Optional vision metadata also supports local-client photo model discovery |
| `POST /v1/chat/completions` | Text/image input and client-executed functions, buffered or SSE when `stream: true` |
| `POST /v1/audio/transcriptions` | Offline CPU file transcription; multipart, JSON/text/verbose JSON response |
| `POST /v1/audio/speech` | Offline CPU TTS; JSON request, buffered 24 kHz mono PCM16 WAV/raw PCM response |
| `OPTIONS` on any endpoint | CORS preflight when CORS is enabled |

A single repeated `/v1` prefix is accepted as an alias, such as
`/v1/v1/chat/completions`. FitBuddy 3.2.81's Refresh probe appends `/v1` even
when its configured base already includes it. The alias uses the same routing,
authentication and validation; ordinary endpoint URLs remain the canonical form.

Requests must specify the exact model ID and 1–128 messages ending in a user
turn or the final result of a completed tool round. Roles are `user`, `assistant`, `tool`, and leading `system` instructions;
leading `developer` instructions map to `system`. Content accepts a text
string or an array of `{"type":"text","text":"..."}` parts and, for user
turns, `{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}`
parts. PNG/JPEG/WebP still images are decoded and normalized locally. Up to
four images across the conversation are accepted, limited to 2 MiB each,
4 MiB total before base64, 16 megapixels, and 8192 pixels per side. Remote URLs
and API file paths return 400; `detail` must be `auto` or omitted.
See [image understanding](image-understanding.md) for examples, lifecycle,
normalization, and the measured NPU encoder/CPU adapter arrangement. Optional
message `name` fields are accepted as metadata. On tool results, an optional
name must match the function identified by `tool_call_id`.
The entire supplied history reaches LiteRT-LM's model chat template; it is
not flattened into a last-message prompt. Each request creates a fresh
conversation and KV session while retaining the loaded engine.

| Parameter | Supported values |
| --- | --- |
| `stream` | Boolean, default `false` |
| `enable_thinking` | Boolean; overrides the saved API thinking default (initially `false`) |
| `reasoning_effort` | `none` disables thinking; `minimal`, `low`, `medium`, `high`, `xhigh`, `max` enable it |
| `reasoning` | Object with optional `enabled` boolean and/or `effort` string; its presence opts into thinking unless explicitly disabled |
| `tools` | Up to 128 client-executed function definitions; at most 256 KiB total |
| `tool_choice` | `auto` or `none`; defaults to `auto` with functions, otherwise `none` |
| `max_tokens` or `max_completion_tokens` | Integer 1–4096; default 256, or 2048 with thinking or JSON object output enabled; supply only one |
| `response_format` | `{"type":"text"}` (default) or `{"type":"json_object"}`; JSON Schema output is unsupported |
| `temperature` | 0–2; zero selects greedy sampling |
| `top_p` | Greater than 0 and at most 1 |
| `seed` | Signed 32-bit integer; deterministic output is not guaranteed |
| `n` | 1 |
| `stream_options.include_usage` | Boolean, with `stream: true` |
| `user` | Optional string metadata; not passed to the model or logged |
| `prompt_cache_key` | Optional metadata string of at most 256 characters; no cross-request prompt cache is used |

Omitted sampling parameters preserve the verified upstream defaults. Sampling
uses the pinned runtime's existing CPU sampler with the same NPU inference
backend; this is not a CPU inference fallback. Native float precision limits
apply to sampling values. Zero frequency/presence penalties and `logprobs: false`
are also accepted. Other parameters, forced tool choices, strict schemas, stop
sequences, log probabilities, JSON Schema output, inline audio content, and
multiple candidates receive explicit errors.

Non-streaming responses contain `id`, `object: "chat.completion"`, `created`,
`model`, and one assistant message in `choices`. `finish_reason` is `stop`,
`tool_calls` for complete function calls, or `length` when the measured decode
count reaches the output cap. Usage comes
from upstream prefill/decode measurements, including the model's chat template;
the server does not estimate tokens from characters. If measurements are
unavailable, non-streaming usage is omitted.

Streaming responds with `Content-Type: text/event-stream`, sends an initial
assistant role delta, then native text deltas as they arrive, an empty delta
with the finish reason, and `data: [DONE]`. All frames share one ID and creation
timestamp. `include_usage` adds `usage: null` to ordinary chunks and a final
empty-choices usage chunk (null if unavailable). Heartbeat comments keep idle
streams responsive while loading/prefilling. An error after headers is sent
as a JSON error in a data event followed by `[DONE]`; it does not claim success.

## JSON object output

`response_format: {"type":"json_object"}` adds a JSON-only formatting instruction
to the request's leading system instructions and raises the default output cap
to 2048. Explicit token caps and reasoning controls still apply. Supply the
desired field names and types in your prompt; this mode does not enforce a
domain schema or validate the accuracy of generated values.

The server validates that generated content is one strict JSON object before
publishing it. This uses prompting and output validation, not grammar-constrained
decoding. A completed malformed object gets one syntax-only repair attempt when
there are no enabled tools and output tokens remain. Both attempts together
respect the requested output cap, and usage includes both. Native failures,
cancellation and token truncation are not retried. Invalid or incomplete JSON returns HTTP 500 with code
`invalid_json_output`; a capped incomplete result asks the client to increase
`max_tokens` or simplify the request. Complete function calls are returned using
the usual `tool_calls` contract.

JSON-mode SSE sends the initial assistant role and heartbeat comments while
generating, then publishes validated content and separate reasoning, followed
by the finish reason, optional usage and `[DONE]`. Invalid JSON produces an SSE
error followed by `[DONE]`, without publishing malformed content. Ordinary text
SSE continues forwarding native text and reasoning deltas live.

`GET /v1/models` retains its standard `data` entries and exact IDs. Gemma also
advertises text/image input and text output in `architecture`, plus a parallel
`models` entry with `completion` and `vision` capabilities for local clients
such as FitBuddy. Audio models remain in `data`; an unconfigured Gemma is never
advertised as a vision model.

## Optional thinking

Set `enable_thinking: true` on a chat request to enable Gemma's thinking mode for
that request only. When clients omit all reasoning controls, the saved
**Use thinking when the client omits reasoning controls** setting applies.
It starts disabled. An explicit false/`none` overrides that setting. The flag reaches the
pinned LiteRT-LM conversation configuration; clients do not need to insert
special tokens into system prompts. The UI continues using direct responses.

`reasoning_effort` and `reasoning.effort` are compatibility controls for the same
binary switch; Gemma does not implement distinct effort levels. Conflicting
controls return 400. Separate reasoning token budgets (`reasoning.max_tokens`
or `thinking_budget`) are unsupported: thinking and the answer share the
request's output cap. Agora 2.1.0's custom provider does not transmit its thinking
toggle for this Gemma model; use the saved server default as described in
[Agora compatibility](agora-compatibility.md).

```json
{
  "model": "gemma-4-E2B-it_Google_Tensor_G5",
  "messages": [{"role": "user", "content": "Explain why a 20% increase followed by a 20% decrease does not cancel out."}],
  "enable_thinking": true,
  "max_completion_tokens": 2048,
  "stream": true
}
```

`enable_thinking` and `reasoning_content` are InferDroid compatibility extensions,
not promises that every OpenAI client understands those fields. In a buffered
response, the assistant message contains the answer in `content` and, when
non-empty, thoughts in `reasoning_content`. Live SSE routes thoughts through
`delta.reasoning_content` and answer text through `delta.content`. Gemma's
`<|channel>thought` / `<channel|>` delimiters are parsed by LiteRT and are not
included in either field. Both channels stream incrementally and share the
same bounded transport queue and cancellation ownership.

The output budget covers **both reasoning and the final answer**. Thinking
requests default to 2,048 output tokens, while direct requests keep the 256-token
default. Either mode accepts an explicit maximum from 1 through 4,096. A limit
hit during thoughts returns `finish_reason: "length"`, potentially with empty
`content`; the partial reasoning is not promoted to an answer. Clients should
check the finish reason and avoid appending an empty assistant answer to history.
Usage counts include generated thoughts as part of `completion_tokens`.

For subsequent requests, send the previous assistant's final `content` as
history. An assistant history message may also include string or null
`reasoning_content` when a client echoes the returned message, but InferDroid
discards that field before prefill. Other roles cannot use it. Reasoning and the
explicit per-request thinking setting does not carry over to later requests;
the saved server default remains in effect. This follows
[Gemma's multi-turn guidance](https://ai.google.dev/gemma/docs/capabilities/thinking#multi-turn-example-with-thought-stripping).

Thinking can increase latency substantially, and a higher cap is not a guarantee
that every response finishes. See the [Pixel reasoning experiment](reasoning-verification.md)
for the measured tradeoffs on the exact G5 model package and the
[native thinking API verification](thinking-api-verification.md) for implementation checks.

## Client-executed functions

Supply OpenAI-style `tools` entries with `type: "function"` and a `function`
object containing `name`, optional `description`, and optional `parameters`.
Omitting parameters defines a function with no arguments. Names contain
1–64 ASCII letters, digits, underscores or hyphens and must be unique.
Parameters use an explicit `object` schema with typed properties, optional
`required` names, and homogeneous `items` schemas for arrays. Nested objects,
string enums and nullable types are accepted. Schema references/combinators
and `strict: true` return explicit errors; argument generation is unconstrained.
Validate arguments in the client before execution.

The NPU receives declarations through the pinned Gemma template. A completed
response contains `message.tool_calls`, each with an ID, `type: "function"`,
and `function.name`/`function.arguments` (a JSON-object string). Tool-only
assistant replies have `content: null`. SSE emits the complete call array as
`delta.tool_calls`, with an `index` for each call, before the terminal
`finish_reason: "tool_calls"`. Thoughts and ordinary text still arrive live;
executable arguments wait until decode completion. Length-limited responses
never expose executable calls.

The client executes the functions, echoes the assistant message, and appends
one `role: "tool"` message per call with the matching `tool_call_id` and a
string/text-part `content` result. Results can arrive in any order. IDs must be
unique across the supplied history; missing, orphaned or duplicate results
return 400 before inference. A following request can end in the completed tool
round. Assistant `reasoning_content` is accepted and stripped as usual.
`tool_choice: "none"` accepts definitions but excludes them from generation.

InferDroid never executes a requested tool or contacts a search provider.
Tool permissions and execution remain with the client. This implements the
client-side mode in the [handoff](../pixel_local_ai_server_codex_handoff.md#18-future-tool-layer-and-optional-server-side-web-search);
server-side orchestration is still future work.

## Admission, cancellation, and transport limits

UI and API chat/ASR/TTS work share one admission gate. Loading, importing,
generation, transcription, synthesis, or stopping rejects additional work with **429**, code `engine_busy`, and
`Retry-After: 1`. There is no inference queue. Model listing and preflight do
not require an idle engine. A socket disconnect cancels only that socket's
request, including during automatic loading; stale cancellation cannot affect
a later client or UI request. The next request is admitted after native work
has drained. Cancellation never frees live native callbacks.
Speech cancellation is cooperative between decoding operations/chunks; the
current sherpa native decode must return before another request can start.
TTS similarly checks cancellation between bounded text chunks and waits for
the synchronous native synthesis to drain before releasing its slot.

The transport uses HTTP/1.1 with one request per connection and
`Connection: close`. JSON POST bodies require `Content-Length` and
`Content-Type: application/json`. SSE is delimited by connection close;
clients should stop at `[DONE]`. Incoming chunked bodies, Expect handshakes,
and HTTP pipelining are unsupported.

Limits: 6 MiB chat JSON including base64 images, 256 KiB total chat text,
256 KiB TTS JSON, audio uploads up to 25 MiB plus 16 KiB
multipart metadata, 16 KiB total HTTP headers, 8 KiB header lines,
64 headers, 32 JSON nesting levels, four HTTP workers plus eight pending
connections, and bounded streaming buffers. Header/body intake has a
15-second deadline; accepted inference requests have a ten-minute deadline. Slow
or disconnected clients cannot block a native callback: it only enqueues
text. A full streaming buffer closes that client and cancels its request.
The model's own context capacity can still reject a large history.

Errors have the envelope:

```json
{"error":{"message":"Inference is busy loading, generating, or stopping. Retry when idle.","type":"rate_limit_error","param":null,"code":"engine_busy"}}
```

Other statuses include 400 for invalid JSON/parameters, 401 for a missing or
wrong token, 403 for forbidden Host/Origin, 404 for an endpoint/model not found,
405 for a method mismatch, 408 for intake timeout, 411 for a missing length,
413 for oversized input, 415 for the wrong content type, 417 for Expect, and
500 for cancellation or inference failure, and 503 for an unconfigured model.
Native details stay in the app's
diagnostics and logcat; failed NPU work never triggers a CPU retry. The server
does not log request bodies, bearer tokens, prompts, images, audio, or transcripts.

## Host smoke test through ADB

Start the server in the app, copy its key, and use a loopback-only ADB forward:

```bash
adb forward tcp:18080 tcp:8080
read -r -s -p 'InferDroid local API key: ' INFERDROID_API_KEY
export INFERDROID_API_KEY
curl --fail-with-body http://127.0.0.1:18080/v1/models \
  -H "Authorization: Bearer $INFERDROID_API_KEY"
curl --fail-with-body http://127.0.0.1:18080/v1/chat/completions \
  -H "Authorization: Bearer $INFERDROID_API_KEY" \
  -H 'Content-Type: application/json' \
  --data '{"model":"gemma-4-E2B-it_Google_Tensor_G5","messages":[{"role":"user","content":"Hello, briefly introduce yourself."}]}'
curl --fail-with-body -N http://127.0.0.1:18080/v1/chat/completions \
  -H "Authorization: Bearer $INFERDROID_API_KEY" \
  -H 'Content-Type: application/json' \
  --data '{"model":"gemma-4-E2B-it_Google_Tensor_G5","messages":[{"role":"user","content":"Hello, briefly introduce yourself."}],"stream":true,"stream_options":{"include_usage":true}}'
unset INFERDROID_API_KEY
adb forward --remove tcp:18080
```

The host port may differ from the device port; accepted Host values include
`localhost` and `127.0.0.1` with an optional port. ADB forwarding is for device
verification and is not an app LAN-serving feature. The phone's other apps
connect directly to port 8080 without ADB.

See [the verification record](milestone-verification.md) for physical NPU
results and the README for the reproducible lifecycle/API/audio/vision/thinking tests.
Those instrumentation tests use test-only fake engines to control timing;
actual CPU ASR/TTS and NPU inference were verified separately through the service's
HTTP listener with public test recordings, generated speech, and the original chat prompt.
The native thinking checks can be reproduced with
`python3 scripts/test-thinking-api.py --output /tmp/inferdroid-thinking-api.json`
after starting the debug app's server on the connected Pixel.
