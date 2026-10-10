# Local OpenAI API — milestones 3 and 4

`InferenceService` owns an optional `OpenAiServer` listening **only on
127.0.0.1**, initially port **8080**. The server has no LAN bind setting and
makes no outbound requests. Java's `ChatGateway` routes protocol-neutral
`GenerationRequest` objects to the same `EngineManager` used by the UI.
`TranscriptionGateway` independently routes audio requests to `SpeechManager`.
Both lifecycles share `WorkGate`. No OpenAI account, cloud service, or API
subscription is involved.

## Start and configure

1. Select the existing Tensor G5 model with **Choose model** for chat, or
   [import the separate Whisper bundle](speech-recognition.md) for speech.
2. Scroll to **Local OpenAI API**. Keep **Require local API key** enabled;
   **Copy key** copies the generated key for use in your client. The app masks
   the key and marks the clipboard entry sensitive on Android 13+.
3. Leave port **8080**, or enter another port from 1 through 65535. Enable
   **Allow browser / WebView clients (CORS)** if your client needs it.
4. Press **Start server**. The displayed URL is the client's base URL.
   Starting the listener does not load models; the first request loads its
   engine automatically. **Load model** / **Load speech** can initialize them
   beforehand. A speech-only listener does not need a Gemma selection.
5. The service and notification remain active while the server is listening,
   even before a model is loaded or after an initialization failure. Leaving
   the Activity does not stop the listener.

| Client setting | Value |
| --- | --- |
| OpenAI base URL | `http://127.0.0.1:8080/v1` |
| API key | The key copied from InferDroid |
| Chat model ID | `gemma-4-E2B-it_Google_Tensor_G5` |
| Speech model ID | `sherpa-onnx-whisper-tiny` |

Port, key, authentication choice, and CORS choice are saved privately. Stop
the listener before changing them or choosing a different model. **Generate
new key** replaces the saved key; clients must use it after the next start.
Authentication can be explicitly disabled for clients that require it, but
localhost is accessible to other apps in the same Android profile.

**Stop server** closes the listener and its client connections, cancels its
active request, and leaves loaded models available to the UI. **Unload /
Stop**, including the notification action, closes the listener, drains native
work, unloads both engines, and stops the service. Initialization remains
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
cannot override a client's policy; test each client separately. Agora,
FitBuddy, and RPClient have not yet been verified with this server.

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
Chat implements the following text subset; the independent audio contract
and multipart examples are in [speech-recognition.md](speech-recognition.md).

| Endpoint | Behavior |
| --- | --- |
| `GET /v1/models` | OpenAI-style `list` with the configured Gemma and imported Whisper model, including while unloaded; unconfigured engines are omitted |
| `POST /v1/chat/completions` | One completion, or incremental SSE when `stream: true` |
| `POST /v1/audio/transcriptions` | Offline CPU file transcription; multipart, JSON/text/verbose JSON response |
| `OPTIONS` on any endpoint | CORS preflight when CORS is enabled |

Requests must specify the exact model ID and 1–128 messages ending in a user
turn. Roles are `user`, `assistant`, and leading `system` instructions;
leading `developer` instructions map to `system`. Content accepts a text
string or an array of `{"type":"text","text":"..."}` parts. Optional
message `name` fields are accepted as metadata and are not passed to Gemma.
The entire supplied history reaches LiteRT-LM's model chat template; it is
not flattened into a last-message prompt. Each request creates a fresh
conversation and KV session while retaining the loaded engine.

| Parameter | Supported values |
| --- | --- |
| `stream` | Boolean, default `false` |
| `max_tokens` or `max_completion_tokens` | Integer 1–1024, default 256; supply only one |
| `temperature` | 0–2; zero selects greedy sampling |
| `top_p` | Greater than 0 and at most 1 |
| `seed` | Signed 32-bit integer; deterministic output is not guaranteed |
| `n` | 1 |
| `stream_options.include_usage` | Boolean, with `stream: true` |
| `user` | Optional string metadata; not passed to the model or logged |

Omitted sampling parameters preserve the verified upstream defaults. Sampling
uses the pinned runtime's existing CPU sampler with the same NPU inference
backend; this is not a CPU inference fallback. Native float precision limits
apply to sampling values. Compatibility defaults `tools: []`,
`tool_choice: "none"`, `response_format: {"type":"text"}`, zero
frequency/presence penalties, and `logprobs: false` are also accepted.
Other parameters, tool calls, stop sequences, log probabilities, structured
output, image/audio content, and multiple candidates receive explicit errors.

Non-streaming responses contain `id`, `object: "chat.completion"`, `created`,
`model`, and one assistant message in `choices`. `finish_reason` is `stop`,
or `length` when the measured decode count reaches the output cap. Usage comes
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

## Admission, cancellation, and transport limits

UI and API chat/speech work share one admission gate. Loading, importing,
generation, transcription, or stopping rejects additional work with **429**, code `engine_busy`, and
`Retry-After: 1`. There is no inference queue. Model listing and preflight do
not require an idle engine. A socket disconnect cancels only that socket's
request, including during automatic loading; stale cancellation cannot affect
a later client or UI request. The next request is admitted after native work
has drained. Cancellation never frees live native callbacks.
Speech cancellation is cooperative between decoding operations/chunks; the
current sherpa native decode must return before another request can start.

The transport uses HTTP/1.1 with one request per connection and
`Connection: close`. JSON POST bodies require `Content-Length` and
`Content-Type: application/json`. SSE is delimited by connection close;
clients should stop at `[DONE]`. Incoming chunked bodies, Expect handshakes,
and HTTP pipelining are unsupported.

Limits: 256 KiB chat JSON bodies, audio uploads up to 25 MiB plus 16 KiB
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
does not log request bodies, bearer tokens, prompts, audio, or transcripts.

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
results and the README for the sixteen reproducible lifecycle/API/audio tests.
Those instrumentation tests use test-only fake engines to control timing;
actual CPU ASR and NPU inference were verified separately through the service's
HTTP listener with public test recordings and the original chat prompt.
