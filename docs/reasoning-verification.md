# Gemma E2B reasoning — Pixel verification, 2026-10-12

The exact `gemma-4-E2B-it_Google_Tensor_G5.litertlm` package can reason and
generate Gemma thought blocks on the Pixel 10's Tensor G5 path. Explicit
thinking increased latency and output length in this small experiment without
improving completed-answer correctness. The existing output ceiling can prevent
thinking requests from completing.

## Device and method

The connected Pixel 10 ran Android API 37 / GrapheneOS with the existing
3,113,545,589-byte model in `AIModels/litert-npu`. The installed debug APK
reported **versionName 0.1.0**; its SHA-256 was
`cfd1427a7230809b081270793a282fb75d44fd6eddc8376aad116662864b30a6`.
No APK was rebuilt or installed. These results verify the model through that
installed artifact, rather than constitute a device regression of the repository's
current 0.8.0 build.

All inference stayed on the phone. The authenticated localhost chat API was
accessed through a temporary loopback ADB forward. The saved key was read using
the debug app's `run-as` access and never printed or saved in the test reports.
The listener started for the experiment was stopped afterward, and the model
was unloaded to restore the original state. Existing settings were preserved.

Runtime logs identified the APK's `libLiteRtDispatch_GoogleTensor.so`,
`Found GoogleTensorOptions`, SouthBound context creation and symbols resolved by
`libedgetpu_litert.so`. Requests reused the retained Tensor G5 engine. The
device's overall thermal status was 0 before the main suite and after testing.

The main suite paired eight synthetic problems with `temperature: 0`, `seed: 42`,
`max_tokens: 1024`, live SSE and actual runtime usage counts. Both modes used:

```text
Answer concisely. Put your final answer on a line formatted ANSWER: value.
```

Thinking requests differed only by a `<|think|>` token and newline prepended
to that system message. This follows the system-marker mechanism shown in
[Google's Gemma thinking documentation](https://ai.google.dev/gemma/docs/capabilities/thinking).
The response itself confirmed activation: every thinking request emitted
`<|channel>thought`, while no direct request did. Completed thought blocks
ended with `<channel|>` before the final response.

The experiment used a prompt marker rather than a dedicated API option. The
subsequent implementation adds the optional [`enable_thinking` API setting](local-api.md#optional-thinking)
and separate reasoning output; the results below describe the original experiment.
The pinned LiteRT-LM header exposes `SetEnableThinking(bool)` with a default of
false, but this experiment did not modify native configuration or rebuild it.
See the [pinned conversation API](https://github.com/google-ai-edge/LiteRT-LM/blob/0b98b80e1d846af27d10b8ab395645119cb231e4/runtime/conversation/conversation.h).

## Main results

| Problem | Expected answer | Direct: seconds / output tokens | Thinking: seconds / output tokens | Thinking result |
| --- | --- | --- | --- | --- |
| Multiplication | 391 | 6.383 / 109 | 12.618 / 225 | Correct, complete |
| Increase then decrease by 20% | 96 | 13.101 / 234 | 16.289 / 293 | Correct, complete |
| Combined pump rates | 4 hours | 21.892 / 393 | 26.209 / 478 | Correct, complete |
| Probability without replacement | 3/10 | 30.271 / 550 | 32.741 / 598 | Correct, complete |
| Python loop trace | 8 | 15.770 / 282 | 26.741 / 485 | Correct, complete |
| Invalid converse deduction | NO | 0.640 / 6 | 25.800 / 468 | Correct, complete |
| Four-person ordering constraints | 1 | 38.527 / 702 | 56.078 / 1024 | Truncated during thoughts |
| Average train speed, Italian | 72 km/h | 22.753 / 409 | 56.240 / 1024 | Truncated during final explanation |

Direct mode produced **8/8 correct, complete answers**; thinking produced
**6/8 correct, complete answers and two truncated responses**. Both truncated
outputs had reached the correct numerical result, but did not finish their
requested final answer. They are completion failures, not evidence that the
underlying model could not solve those problems. The Italian response already
included the correct equation in its final explanation when the cap intervened.

The ordering answer was independently checked by enumerating all 24 permutations:
only `ACDB` satisfies all three constraints. Numeric answers with their requested
units, such as `4 hours`, count as correct. The scorer requires a correct final
`ANSWER:` line, normal `finish_reason: stop`, and the SSE completion sentinel;
text inside an unfinished thought block does not count as a final answer.

Mean elapsed time across the eight requests was **18.67 s direct versus 31.59 s
thinking**, including the two capped requests. Output totals were **2,685 versus
4,595 tokens**. First text arrived in 0.21–0.37 s with the engine already loaded;
for thinking requests this was the start of thoughts, not final-answer latency.
These are observations from one greedy run per problem and mode, not a general
reasoning benchmark or a statistical performance comparison.

## Output-budget checks

The same concise prompt was tested at the default-equivalent 256-token budget:

| Problem | Direct | Thinking |
| --- | --- | --- |
| Multiplication | Correct, 109 tokens | Correct, 225 tokens |
| Probability | Truncated, 256 tokens | Truncated, 256 tokens |
| Deduction | Correct, 6 tokens | Truncated, 256 tokens |

The initial arithmetic smoke test used a more expansive system instruction;
both modes hit 256 tokens. It is retained separately in the evidence and is
excluded from the main comparison. Prompt wording affects response length.

A follow-up added `Keep any reasoning under 100 words.` to the concise system
prompt at the 1,024-token cap. The Italian case then finished correctly in
47.613 s / 865 tokens. The ordering puzzle still exhausted 1,024 tokens in
56.263 s. A brief-reasoning instruction did not reliably enforce a budget.

All 26 HTTP requests returned 200, completed their SSE transport, and used the
same retained engine. Cap exhaustion was reported as `finish_reason: length`.
Later requests succeeded after truncation; no crash or reload was observed.

## Integration implications

Keep direct responses as the default. A supported thinking feature should
offer an explicit control, route it through LiteRT's conversation configuration,
and validate the exact packaged chat template's behavior. It also needs an output
budget large enough for both thoughts and the final answer, with cancellation
and clear handling when that budget is exhausted.

The installed API streamed raw thought delimiters and thought text as ordinary
`delta.content`; it did not separate a reasoning field from the final answer.
That output must be parsed or routed separately before exposing a polished
thinking feature. Conversation history should retain the final response rather
than copy the entire raw thought block into later assistant turns, following
Google's multi-turn guidance.

## Reproduce and inspect

[The compact evidence](evidence/reasoning-2026-10-12.json) preserves all prompts,
generated text, timings, usage and completion status. The original raw SSE frames
were written to `/tmp/inferdroid-reasoning-*-20261012.json` and were lost in the
subsequent workstation reboot. The compact evidence remains in this repository.
No API keys are included.

Start the existing debug app's server, keep its authentication setting, and run:

```bash
python3 scripts/test-device-reasoning.py --output /tmp/inferdroid-reasoning-main.json --modes plain marker
python3 scripts/test-device-reasoning.py --output /tmp/inferdroid-reasoning-budget.json \
  --caps 256 --modes plain marker --cases multiplication probability deduction
python3 scripts/test-device-reasoning.py --output /tmp/inferdroid-reasoning-brief.json \
  --caps 1024 --modes marker --cases arrangements italian \
  --system 'Answer concisely. Keep any reasoning under 100 words. Put your final answer on a line formatted ANSWER: value.'
```

The script requires one authorized ADB device and a debuggable `dev.inferdroid`
installation with its model selected. It creates and removes its own ADB forward
and refuses to overwrite an existing report. Its captures contain only the
synthetic test material and response data; it does not change app settings.
