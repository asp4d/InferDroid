# Offline text to speech — milestone 5

InferDroid generates speech locally with **Supertonic 3 int8**, using
**sherpa-onnx 1.13.8's C API behind JNI** and **ONNX Runtime 1.28.2 on CPU**
with two threads. TTS retains its own four model sessions, independently of
Whisper ASR and Gemma. Gemma's Tensor G5 runtime and pins are unchanged.

## Model and licensing

The initial model is the official sherpa bundle
[`sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2`](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2).
It provides ten stock voices and 31 languages, including Italian, with about
**139 MiB** of required files and no phonemizer dependency. This gives the
first implementation multilingual speech without another inference runtime.
Only this exact export is accepted; importing other TTS families or custom
voice files is unsupported.

Model archive SHA-256:

```text
82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427
```

The **model weights use BigScience OpenRAIL-M**, including its use restrictions,
as published by [Supertone](https://huggingface.co/Supertone/supertonic-3).
The bundle's MIT `LICENSE` covers Supertone's sample code, adapted by the
upstream TTS implementation, and does not replace the model license. Its notice
is retained in the APK as `third_party/supertonic-code-LICENSE.txt`.
InferDroid's own code remains Apache 2.0. The build packages
the [model license at a pinned upstream revision](https://huggingface.co/Supertone/supertonic-3/blob/3cadd1ee6394adea1bd021217a0e650ede09a323/LICENSE),
SHA-256 `0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f`.
Neither weights nor generated audio are bundled in Git or the APK.

`scripts/build-speech.sh` uses the same sherpa and ONNX Runtime archive hashes
as [ASR](speech-recognition.md#prepare-the-runtime-and-model). It enables TTS
in a separate ignored source/build tree. `scripts/prepare-tts-runtime.py`
selects just Supertonic's implementation while preserving C ABI configuration
types and existing ASR sources. Other TTS implementations and eSpeak/piper
are excluded. A Supertonic-specific patch checks predicted audio against the
export's 1000-position capacity before running the vector estimator.
`native/tts_safe_c_api.cc` catches native exceptions inside the sherpa DSO,
where they are thrown, and returns a C status to JNI. Exceptions do not cross
separately linked static C++ runtimes. Error logs redact the caller's text.
The APK still has seven native libraries; TTS extends the
existing `libinferdroid_speech_jni.so`.

## Prepare and import

Build the shared audio runtime if updating from milestone 4:

```bash
export JAVA_HOME=/home/asp4d/.jdks/jbr-21.0.11
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK_HOME="$PWD/.deps/toolchains/android-ndk-r30-beta1"
./scripts/build-speech.sh
./scripts/prepare-tts-model.sh
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell mkdir -p /sdcard/AIModels
adb push .deps/tts/models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11 /sdcard/AIModels/
```

The host script downloads and checksum-verifies the archive. The Android app
does no network downloads. In **Offline text to speech**, tap **Import TTS model
folder**, select the deployed folder, and allow read access. Import copies only
the following seven files into `files/tts/supertonic-3/` in app-private storage
and checks each SHA-256. Their total size is **145,295,768 bytes**; reserve
additional temporary space when replacing an imported bundle.

| Required file | SHA-256 |
| --- | --- |
| `duration_predictor.int8.onnx` | `c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db` |
| `text_encoder.int8.onnx` | `c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff` |
| `vector_estimator.int8.onnx` | `20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd` |
| `vocoder.int8.onnx` | `e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152` |
| `tts.json` | `42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09` |
| `unicode_indexer.bin` | `8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30` |
| `voice.bin` | `67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8` |

The folder switch is atomic, with recovery if the process dies between renames.
Failed/cancelled import preserves the previous bundle. Unrecognized extra
files are ignored; mismatched required files are rejected. Import unloads only
TTS, and the listener must be stopped first. No broad storage permission is used.

## Use speech in the app

Enter text, choose **F1–F5** (female) or **M1–M5** (male), set a language code
and speed, then tap **Generate speech**. The first request loads Supertonic;
later requests reuse it. **Load TTS** initializes beforehand and **Unload TTS**
releases only TTS. The top **Cancel** and **Unload / Stop** include TTS work.
Voice, language, and speed preferences are saved; input text/audio are not
written to preferences or audio cache files.

**Play** previews the latest result through Android AudioTrack; **Stop audio**
stops preview. Leaving the Activity stops playback while service generation
can continue. **Save WAV** opens `ACTION_CREATE_DOCUMENT` and writes only to
the destination chosen by the user. Generation and export run off the UI thread.
The latest generated audio remains in service memory until replaced or unloaded.

The pinned bundle's `tts.json` specifies **44,100 Hz** native output.
The JNI bridge linearly resamples it to **24,000 Hz, mono, signed 16-bit
little-endian PCM**, for both WAV and raw PCM. WAV has a standard RIFF/PCM header;
raw PCM has no header. A five-step synthesis schedule and seed 42 are fixed
backend choices, not client parameters. Naturalness/pronunciation can vary
with language, voice, input punctuation, and speed; these checks are functional
verification rather than a voice-quality benchmark.

## OpenAI-compatible speech endpoint

Send JSON to **`POST /v1/audio/speech`** after starting the local server. The
same bearer token, CORS setting, loopback bind, and transport limits apply as
[chat/ASR](local-api.md). Discovery lists `sherpa-onnx-supertonic-3-int8` after
import, even while unloaded. No Gemma or ASR model is needed to serve TTS.
The request subset follows the official
[Audio Speech schema](https://developers.openai.com/api/reference/typescript/resources/audio/subresources/speech/methods/create).

| Parameter | Support |
| --- | --- |
| `model` | Required: `sherpa-onnx-supertonic-3-int8` or alias `tts-1` |
| `input` | Required nonblank Unicode text containing letters/numbers; at most 4096 Java UTF-16 code units; no NUL/unpaired surrogates |
| `voice` | Required: `F1`–`F5`, `M1`–`M5`, or an alias below |
| `language` | Local extension; default `en`; explicit supported code below, including `it` |
| `response_format` | `wav` (default, `audio/wav`) or `pcm` (`application/octet-stream`) |
| `speed` | Number from 0.25 through 2.0; default 1.0 |
| `instructions` | Empty or omitted only |
| `stream_format` | `audio` or omitted only |
| `stream` | `false` or omitted only |

WAV is deliberately the local default. MP3, AAC, Opus, FLAC, speech SSE,
voice instructions, cloning, unknown parameters, duplicate fields, and
invalid types return explicit 400 errors. `tts-1` selects this same local
Supertonic model. Compatibility names map to stock voices, without reproducing
OpenAI voice identities:

| Alias | Local voice |
| --- | --- |
| `alloy` | F1 |
| `echo` | M1 |
| `fable` | M2 |
| `onyx` | M3 |
| `nova` | F2 |
| `shimmer` | F3 |

Supported codes:

```text
en ko ja ar bg cs da de el es et fi fr hi hr hu id it lt lv nl pl pt ro ru sk sl sv tr uk vi
```

Only English and Italian generation have been physically checked in this
milestone; the remaining languages follow the pinned model's supported list.
Language is explicit, with no automatic detection or mixed-language promise.
The speed range is deliberately narrower than OpenAI's: native 4.0 produced
silent short utterances on the Pixel, while 0.25 and 2.0 produced valid audio.
Faster values are rejected before inference; a silent native result is an
engine error, not a successful audio response.

Output is bounded to **120 seconds / 5,760,000 PCM bytes**. Text is split into
chunks of at most 240 UTF-16 code units, preferring punctuation/whitespace
boundaries and preserving characters; chunks have 200 ms of intervening silence.
Chunks exceeding the model's predicted-duration capacity are retried with a
shorter prefix, preserving the remaining input. Whitespace-only pieces are
skipped because the native normalizer trims them. Extreme speed/input values
receive the same total-duration limit, without overrunning the positional table.
Oversized input or generated audio returns 413 rather than silently truncating.
Longer documents require separate client requests.

Audio is buffered completely before a 200 response with exact Content-Length;
there is no incremental TTS delivery. Chat, ASR, and TTS share one active-work
slot, with **429 `engine_busy` / Retry-After: 1** for overlaps. Disconnect,
Cancel, or server stop flags only the owning request. Supertonic's synchronous
native call cannot be aborted here: cancellation is checked between chunks,
discards cancelled audio, and retains ownership until the current call drains.
Full Unload / Stop waits for all three engine workers before ending foreground
state. Idle engines hold no inference wake lock.

Missing bundle returns 503 `tts_model_unavailable`; unknown model returns 404;
cancellation returns 500 `request_cancelled` when the client remains connected;
engine failure returns 500 `synthesis_failed`. Input text, generated samples,
and local keys are not logged or uploaded.

Example from the host through ADB:

```bash
adb forward tcp:18080 tcp:8080
read -r -s -p 'InferDroid local API key: ' INFERDROID_API_KEY
curl --fail-with-body http://127.0.0.1:18080/v1/audio/speech \
  -H "Authorization: Bearer $INFERDROID_API_KEY" \
  -H 'Content-Type: application/json' \
  --data '{"model":"sherpa-onnx-supertonic-3-int8","input":"Ciao! Questa voce viene generata sul dispositivo.","voice":"F1","language":"it","speed":1.0,"response_format":"wav"}' \
  --output inferdroid-speech.wav
unset INFERDROID_API_KEY
adb forward --remove tcp:18080
```

Check the HTTP status before playing a response; a failed request contains
JSON instead of audio. Other apps on the phone use port 8080 directly.

## Verification and sources

The Pixel 10 / GrapheneOS generated real English and Italian WAVs, raw PCM,
and shorter audio at increased speed. UI import, Android preview, and document
export were checked. English synthesized audio transcribed back correctly with
the existing Whisper engine. The Italian round trip contained errors; this
does not isolate TTS pronunciation from Whisper tiny recognition, and does
not establish Italian accuracy. No user recordings were used.
See [the verification record](milestone-verification.md) for timing,
background/cancellation/contention checks, NPU regressions, and build evidence.

- [Official Italian Supertonic example](https://k2-fsa.github.io/sherpa/onnx/tts/all/Italian/supertonic-3-it.html)
- [Pinned Supertonic implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-tts-supertonic-impl.cc)
- [Pinned native C API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/c-api/c-api.h)
- [Voice ordering in the exporter](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/scripts/supertonic/generate_voices_bin.py)
