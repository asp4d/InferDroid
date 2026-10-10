# Offline speech recognition — milestone 4

InferDroid transcribes local audio files using **sherpa-onnx 1.13.8** and
**multilingual Whisper tiny int8**. This is an independent **CPU** engine with
two ONNX Runtime threads. Gemma remains on the verified Tensor G5 NPU path.
Neither inference engine makes outbound requests. No OpenAI account is needed.

## Prepare the runtime and model

Build the speech runtime once alongside the existing LiteRT runtime:

```bash
export JAVA_HOME=/home/asp4d/.jdks/jbr-21.0.11
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK_HOME="$PWD/.deps/toolchains/android-ndk-r30-beta1"
./scripts/build-speech.sh
./scripts/prepare-speech-model.sh
./gradlew :app:assembleDebug :app:lintDebug
```

The build script needs Bash, CMake, a C++ build tool (`make`), curl, tar,
unzip, Python, rg, and the pinned NDK. `SPEECH_JOBS` defaults to 6. It builds
the C API from the pinned source archive, with TTS, diarization, microphone
helpers, WebSockets, and upstream JNI disabled. It does not rebuild or change
LiteRT. ONNX Runtime is the exact Android binary selected by this sherpa tag's
build recipe. Dependency notices are packaged in the APK.

| Input | Version/source | Archive SHA-256 |
| --- | --- | --- |
| sherpa-onnx source | [v1.13.8](https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v1.13.8.tar.gz) | `b0374cc56dbc186d442ae73d5de743bb092470b640c4c50ce7b029044c0c4fa8` |
| ONNX Runtime Android | [1.28.2, upstream maintainer archive](https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip) | `01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d` |
| Whisper tiny bundle | [Official sherpa ASR model release](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2) | `c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1` |

Generated runtime libraries live in `native/artifacts/speech/arm64-v8a/`;
the matching C header lives under `native/artifacts/speech/include/`.
`VERSIONS.txt` and `SHA256SUMS` record the prepared binaries. CMake refuses to
build without the libraries, header, and license notices. AGP packages
`libinferdroid_speech_jni.so`, `libsherpa-onnx-c-api.so`, and
`libonnxruntime.so` alongside the four original G5 libraries.

The model-preparation script extracts only the required int8 files and public
test recordings into `.deps/speech/models/sherpa-onnx-whisper-tiny/`. Model
weights are **not** bundled in the APK or Git. The three required files total
**103,609,903 bytes** (about 99 MiB):

| File | SHA-256 required by the importer |
| --- | --- |
| `tiny-encoder.int8.onnx` | `d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434` |
| `tiny-decoder.int8.onnx` | `d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925` |
| `tiny-tokens.txt` | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |

Deploy the model once:

```bash
adb shell mkdir -p /sdcard/AIModels/sherpa-onnx-whisper-tiny
adb push .deps/speech/models/sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx /sdcard/AIModels/sherpa-onnx-whisper-tiny/
adb push .deps/speech/models/sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx /sdcard/AIModels/sherpa-onnx-whisper-tiny/
adb push .deps/speech/models/sherpa-onnx-whisper-tiny/tiny-tokens.txt /sdcard/AIModels/sherpa-onnx-whisper-tiny/
```

In **Offline speech recognition**, tap **Import speech model folder**, select
that folder, and allow access to it. The importer copies just the three
verified files into app-private storage because the native runtime needs
filesystem paths. Reserve another 99 MiB there, plus temporary space during
replacement. A failed/cancelled import preserves the previous bundle. An
interrupted directory switch recovers the previous bundle at the next service
creation. No broad shared-storage permission is used.

This exact bundle is initially supported; other Whisper exports and ASR model
families are rejected by checksum. Importing a new bundle unloads only speech.
Stop the HTTP listener before replacing models. `sherpa-onnx` is Apache 2.0,
ONNX Runtime and Whisper are MIT licensed; packaged notices identify the
compiled dependencies. The ASR build does not include TTS phonemizers.

## Use files in the app

Tap **Choose audio file**, select a local recording with the system picker,
optionally enter a spoken language code such as `en` or `it`, then tap
**Transcribe audio**. Empty language enables detection. The result and actual
CPU status appear in the speech section. **Load speech** can initialize the
model first; otherwise the first transcription loads it. **Unload speech**
releases only the speech model. The top **Cancel** and **Unload / Stop**
controls also apply to active speech work.

No microphone permission or microphone capture is used in this milestone.
The app opens only the chosen file. It saves a persistent URI when the provider
supports one; choose the file again if permission is revoked. HTTP uploads are
decoded from bounded memory and never written to audio cache files. Audio and
transcripts are not logged or uploaded. The latest transcript remains visible
in the service/UI until replaced or unloaded.

## Audio and recognition limits

| Limit | Behavior |
| --- | --- |
| File size | Up to 25 MiB |
| Decoded duration | Up to 120 seconds; excess returns 413 |
| PCM input | Mono/stereo, 8–192 kHz; downmixed and linearly resampled to mono 16 kHz |
| WAV | Little-endian RIFF, PCM 8/16/24/32-bit or float32; standard extensible PCM/float subtypes with equal valid/container bit width |
| Compressed audio | Android MediaExtractor/MediaCodec; MP3, M4A/AAC, FLAC, Ogg/Vorbis, and WebM/Opus passed on the Pixel |
| Unsupported/corrupt input | 400 `invalid_audio`; use PCM WAV when a device codec is unavailable |
| Audio decoding | 30-second processing deadline, with cooperative cancellation |
| Recognition | Greedy decoding; 25-second consecutive chunks for longer input |
| Language | Whisper's 99 supported codes, including English and Italian; omitted/empty enables detection |

The pinned Whisper implementation truncates input near 30 seconds. InferDroid
splits longer audio into 25-second pieces and processes every sample, then
joins text with spaces. This prevents silent truncation but does not provide
overlap-aware segmentation: words spanning a boundary can be recognized poorly.
Language detection on the first chunk supplies subsequent chunks' hint.
Whisper tiny prioritizes memory and speed; noise, names, silence, repetitions,
and mixed languages can produce mistakes or hallucinated text. It has no
separate VAD stage, confidence score, or timestamp alignment here.

## OpenAI-compatible transcription endpoint

Start the existing local server and send **multipart/form-data** to
`POST /v1/audio/transcriptions`, using the same local key and optional CORS
settings as chat. Speech can be used without configuring Gemma. Discovery
lists `sherpa-onnx-whisper-tiny` once its bundle is imported, even while unloaded.
`whisper-1` is also accepted as a compatibility alias for this same local tiny
model; it does not select a cloud or larger Whisper model.

The supported subset follows the official
[transcription request/response schema](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create).

| Multipart field | Support |
| --- | --- |
| `file` | Required, one non-empty binary file part with a filename; filename is ignored as metadata |
| `model` | Required: `sherpa-onnx-whisper-tiny` or compatibility alias `whisper-1` |
| `language` | Optional supported Whisper language code; empty/omitted means detection |
| `response_format` | `json` (default), `text`, or `verbose_json` |
| `temperature` | Optional zero only; backend uses greedy decoding |
| `prompt` | Empty only; prompting is unsupported |
| `stream` | `false` only; no live ASR/SSE in this milestone |

JSON returns `{"text":"..."}`. Text returns UTF-8 `text/plain`.
Verbose JSON adds `task: "transcribe"`, `language` as a Whisper code, and
measured decoded `duration` in seconds; no segments, words, timestamps, or
confidence fields are fabricated. Translation, diarization, SRT/VTT, nonzero
temperature, non-empty prompts, streaming, and other fields receive explicit
400 errors. Duplicate fields/files, malformed boundaries, and invalid UTF-8
form fields are rejected. Multipart accepts up to 16 parts, 2 KiB headers per
part, 1 KiB per text field, and 16 KiB total metadata beyond the file limit.

Chat and speech use **one shared active-work slot**. An overlapping UI/API
operation receives 429 `engine_busy`; there is no inference queue. Both models
may remain loaded, and their backends are shown separately. HTTP disconnect
and server stop cancel only the owning request. sherpa's offline decode has
no abort operation: cancellation stops decoding/copy work at cooperative
boundaries and waits for the current native chunk to finish. A cancelled
transcript is discarded; the slot and model remain owned until native work
drains. Full **Unload / Stop** waits for both independent engine workers before
removing foreground status. Idle models do not hold wake locks.

The server returns 503 `speech_model_unavailable` before inference when no
bundle is imported; 413 for size/duration limits; 400 for invalid audio/options;
and 500 for cancellation or engine failure. Authentication, CORS, loopback
binding, bounded HTTP workers, 15-second intake deadline, and ten-minute
accepted-request deadline match the [local API transport](local-api.md).

Example through ADB (replace the file path with your recording):

```bash
adb forward tcp:18080 tcp:8080
read -r -s -p 'InferDroid local API key: ' INFERDROID_API_KEY
curl --fail-with-body http://127.0.0.1:18080/v1/audio/transcriptions \
  -H "Authorization: Bearer $INFERDROID_API_KEY" \
  -F 'model=sherpa-onnx-whisper-tiny' \
  -F 'language=it' \
  -F 'response_format=json' \
  -F 'file=@/absolute/path/to/recording.wav'
unset INFERDROID_API_KEY
adb forward --remove tcp:18080
```

## Verification and references

The reference Pixel passed real WAV/encoded-file transcriptions, UI import and
file selection, background serving, Busy/429, disconnect recovery, and Gemma
NPU/SSE regression checks. The test audio comes from public WAV files and
reference transcripts supplied in the official sherpa model archive; no user
recordings were needed. Physical recognition measurements are **English**.
Italian support follows the multilingual model/language API and has not been
measured on an Italian recording yet. See the
[verification record](milestone-verification.md) and README test commands.

Implementation references:

- [Android native build](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html)
- [Offline C API at the pinned tag](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/c-api/c-api.h)
- [Whisper decode, SetConfig, and input-window limit](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h)
- [Multilingual model overview](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/index.html)
