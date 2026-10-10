#!/usr/bin/env bash
# Independent CPU ASR runtime. Never changes the verified LiteRT/G5 stack.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SPEECH="$ROOT/.deps/speech"
NDK="${ANDROID_NDK_HOME:-$ROOT/.deps/toolchains/android-ndk-r30-beta1}"
OUT="$ROOT/native/artifacts/speech/arm64-v8a"
mkdir -p "$SPEECH" "$OUT"
if ! rg -q '^Pkg.Revision = 30\.0\.14904198' "$NDK/source.properties"; then
    printf 'Use the pinned Android NDK r30-beta1 via ANDROID_NDK_HOME.\n' >&2
    exit 1
fi

download() {
    local url="$1" path="$2" digest="$3"
    if [ ! -f "$path" ]; then
        curl --fail --location --retry 3 --output "$path.part" "$url"
        mv "$path.part" "$path"
    fi
    printf '%s  %s\n' "$digest" "$path" | sha256sum --check --status
}
download https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v1.13.8.tar.gz \
    "$SPEECH/sherpa-onnx-v1.13.8-source.tar.gz" b0374cc56dbc186d442ae73d5de743bb092470b640c4c50ce7b029044c0c4fa8
download https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip \
    "$SPEECH/onnxruntime-android-1.28.2.zip" 01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d
if [ ! -f "$SPEECH/source/CMakeLists.txt" ]; then
    mkdir -p "$SPEECH/source"
    tar -xzf "$SPEECH/sherpa-onnx-v1.13.8-source.tar.gz" -C "$SPEECH/source" --strip-components=1
fi
if [ ! -f "$SPEECH/onnxruntime/jni/arm64-v8a/libonnxruntime.so" ]; then
    mkdir -p "$SPEECH/onnxruntime"
    unzip -q "$SPEECH/onnxruntime-android-1.28.2.zip" -d "$SPEECH/onnxruntime"
fi
export SHERPA_ONNXRUNTIME_LIB_DIR="$SPEECH/onnxruntime/jni/arm64-v8a"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$SPEECH/onnxruntime/headers"
cmake -S "$SPEECH/source" -B "$SPEECH/build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384' \
    -DSHERPA_ONNX_ENABLE_C_API=ON -DSHERPA_ONNX_ENABLE_JNI=OFF \
    -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF -DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF -DSHERPA_ONNX_ENABLE_PYTHON=OFF
cmake --build "$SPEECH/build" --target sherpa-onnx-c-api -j "${SPEECH_JOBS:-6}"
cp "$SPEECH/build/lib/libsherpa-onnx-c-api.so" "$OUT/"
cp "$SHERPA_ONNXRUNTIME_LIB_DIR/libonnxruntime.so" "$OUT/"
mkdir -p "$ROOT/native/artifacts/speech/include/sherpa-onnx/c-api"
cp "$SPEECH/source/sherpa-onnx/c-api/c-api.h" "$ROOT/native/artifacts/speech/include/sherpa-onnx/c-api/"

NOTICES="$ROOT/app/src/main/assets/third_party/generated/speech"
mkdir -p "$NOTICES"
cp "$SPEECH/source/LICENSE" "$NOTICES/sherpa-onnx-LICENSE.txt"
python3 "$ROOT/scripts/collect-native-licenses.py" "$SPEECH/build/_deps" "$NOTICES/dependencies.txt"
for name in LICENSE ThirdPartyNotices.txt; do
    filename="$name"
    if [ "$name" = LICENSE ]; then filename=LICENSE.txt; fi
    curl --fail --location --retry 3 --output "$NOTICES/onnxruntime-$filename" \
        "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/$name"
done
curl --fail --location --retry 3 --output "$NOTICES/whisper-LICENSE.txt" \
    https://raw.githubusercontent.com/openai/whisper/v20250625/LICENSE
cat > "$OUT/VERSIONS.txt" <<'EOF'
sherpa-onnx v1.13.8 (source build, ASR, CPU; TTS/JNI/diarization disabled)
ONNX Runtime 1.28.2 (upstream Android binary from csukuangfj/onnxruntime-libs)
arm64-v8a, Android API 31, c++_static, NDK r30-beta1
EOF
(cd "$OUT" && sha256sum ./*.so > SHA256SUMS)
printf 'Speech runtime ready: %s\n' "$OUT"
