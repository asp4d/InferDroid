#!/usr/bin/env bash
# Host-only preparation of the exact tested bundle; no app network downloads.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TTS="$ROOT/.deps/tts"
NAME=sherpa-onnx-supertonic-3-tts-int8-2026-05-11
ARCHIVE="$TTS/$NAME.tar.bz2"
mkdir -p "$TTS/models"
if [ ! -f "$ARCHIVE" ]; then
    curl --fail --location --retry 3 --output "$ARCHIVE.part" \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$NAME.tar.bz2"
    mv "$ARCHIVE.part" "$ARCHIVE"
fi
printf '%s  %s\n' 82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427 "$ARCHIVE" | sha256sum --check --status
tar -xjf "$ARCHIVE" -C "$TTS/models"
printf 'Import the TTS model folder: %s/models/%s\n' "$TTS" "$NAME"
