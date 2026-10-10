#!/usr/bin/env bash
# Host-only preparation; InferDroid itself never downloads model weights.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SPEECH="$ROOT/.deps/speech"
ARCHIVE="$SPEECH/sherpa-onnx-whisper-tiny.tar.bz2"
mkdir -p "$SPEECH/models"
if [ ! -f "$ARCHIVE" ]; then
    curl --fail --location --retry 3 --output "$ARCHIVE.part" \
        https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2
    mv "$ARCHIVE.part" "$ARCHIVE"
fi
printf '%s  %s\n' c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1 "$ARCHIVE" | sha256sum --check --status
tar -xjf "$ARCHIVE" -C "$SPEECH/models" \
    sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx \
    sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx \
    sherpa-onnx-whisper-tiny/tiny-tokens.txt \
    sherpa-onnx-whisper-tiny/test_wavs
printf 'Import these three files from %s/models/sherpa-onnx-whisper-tiny/\n' "$SPEECH"
