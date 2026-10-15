# FitBuddy compatibility — 2026-10-15

FitBuddy's [chat DTO](https://github.com/anantdark/FitBuddy/blob/main/app/src/main/java/com/anant/fitbuddy/data/remote/dto/ChatDtos.kt)
requests `response_format: {"type":"json_object"}` for meal analysis.
InferDroid previously rejected that with HTTP 400 and
`Only response_format={"type":"text"} is supported.` This was reproduced on
the connected Pixel before editing the server; see the
[original response](evidence/fitbuddy-before-2026-10-15.json).
FitBuddy's [remote data source](https://github.com/anantdark/FitBuddy/blob/main/app/src/main/java/com/anant/fitbuddy/data/remote/RemoteAiDataSource.kt)
retries text requests without the format parameter, but does not retry photo
requests this way.

There was also a discovery problem. The photo picker uses advertised native
vision capabilities or model-name heuristics. Its `gemma4` heuristic does not
match InferDroid's `gemma-4-E2B-it_Google_Tensor_G5` ID. The server now includes
the optional `models` capability extension understood by FitBuddy's
[model DTO](https://github.com/anantdark/FitBuddy/blob/main/app/src/main/java/com/anant/fitbuddy/data/remote/dto/ModelsDtos.kt),
alongside its standard OpenAI `data` list. Gemma's architecture also advertises
text/image input and text output.

The installed F-Droid **3.2.81** build has a separate Refresh URL bug. Its
[view model](https://github.com/anantdark/FitBuddy/blob/6fc010cd5c34637dc70371bf4a02fb31758bf9ec/app/src/main/java/com/anant/fitbuddy/ui/viewmodel/MainViewModel.kt#L1496)
appends `/v1/chat/completions` directly to the configured base URL when probing
models, while ordinary chat normalizes a trailing `/v1` first. With the existing
`http://127.0.0.1:8080/v1` setting, Refresh calls
`/v1/v1/chat/completions` and previously received HTTP 404. InferDroid now accepts
one repeated version prefix as an alias through the same authenticated routes.
This lets FitBuddy keep its current URL while standard client URLs still work.

## Setup

1. Update InferDroid, retain the existing Gemma selection, and start its server.
2. In FitBuddy, select **OpenAI-compatible**, or **Ollama** in local mode.
3. Set the host to `http://127.0.0.1:8080` and enter InferDroid's existing API key.
   FitBuddy appends `/v1/chat/completions`. Existing URLs ending in `/v1` also
   work with this InferDroid update, including FitBuddy 3.2.81's Refresh probe.
4. Refresh models and select `gemma-4-E2B-it_Google_Tensor_G5` for both text
   and photo analysis. Allow Network permission for both apps on GrapheneOS.

FitBuddy sends `enable_thinking: false` for local/custom providers. InferDroid
honors this per request, so Agora can retain its saved thinking default.

## Implementation and compatibility

JSON object requests receive a formatting instruction and a 2048-token default
output cap. Explicit caps and reasoning controls still take precedence. Ordinary
text requests retain their existing defaults, histories, live SSE, function
calls and reasoning handling. JNI, native libraries, NPU selection, ASR/TTS,
authentication, ports and saved client settings are unchanged.

The server validates JSON syntax before returning content. A completed malformed
response can receive one syntax-repair generation using the remaining output
budget. Usage counts both attempts. Truncation, cancellation, native failures
and enabled tool calls are not retried. JSON SSE buffers content until
validation; ordinary SSE remains live. An unrepaired or incomplete object
returns `invalid_json_output`, rather than a successful malformed completion.
See the [JSON output contract](local-api.md#json-object-output).

This is JSON object compatibility, not JSON Schema enforcement. FitBuddy's
required field names, types and nutritional estimates remain model-generated;
the server cannot guarantee domain accuracy. The native tests use synthetic
meals and a synthetic non-food image and do not validate nutritional estimates.

## Verification

The normal `dev.inferdroid` installation was updated to the existing source
version **0.9.0**, preserving app data and signing identity. The connected
FitBuddy installation is **3.2.81**. Gradle APK/test APK assembly and lint pass
with zero errors and the same 13 existing warnings.

All **40 instrumentation tests pass**, including the previous 34 lifecycle,
chat, streaming, image, ASR/TTS, reasoning and tool tests. New tests cover JSON
text/photo requests, defaults and explicit controls, strict validation,
stream buffering, malformed/truncated output and recovery, bounded syntax
repair, token accounting, vision discovery and the repeated-version alias with
authentication, SSE and standard-route recovery checks.

The [native FitBuddy probe](evidence/fitbuddy-api-2026-10-15.json) passes all
**eight checks**: model discovery, FitBuddy's actual Refresh probe URL,
four-ingredient meal JSON, photo JSON for a
non-food image, JSON SSE with usage, incomplete-output errors, continued schema
rejection, and ordinary text SSE after JSON requests. The meal used 513 output
tokens across generation and repair, within its 2048-token default budget.

The existing [Agora native regression](evidence/fitbuddy-agora-regression-2026-10-15.json)
also passes all **17 checks**, including buffered/streamed functions, returned
results, same-function result association, reasoning controls, truncation and
recovery. RPClient's existing text API behavior is preserved by the same
regression coverage; its UI has not been independently exercised in this fix.

FitBuddy's actual **OpenAI-compatible** settings were also checked using its
existing localhost URL and selected Gemma models. Refreshing its photo catalog
discovers one vision model; its text catalog lists the three configured chat,
ASR and TTS IDs. No health records or provider credentials were changed. See the
[device summary](evidence/fitbuddy-device-summary-2026-10-15.json).

Reproduce with the server running and its saved thinking default enabled:

```bash
python3 scripts/test-fitbuddy-api.py --output /tmp/fitbuddy-check.json
python3 scripts/test-agora-api.py --default-thinking --output /tmp/agora-check.json
```

The probes keep credentials in memory and remove their ADB forwards. Evidence
contains synthetic inputs and responses only. APK:
`app/build/outputs/apk/debug/app-debug.apk`, SHA-256
`fd625f0696d33239fa40773cefe5a91c7a431e6152327e80c4d4a142b59b7603`.
