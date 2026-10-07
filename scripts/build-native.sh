#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LITERTLM_COMMIT=0b98b80e1d846af27d10b8ab395645119cb231e4
LITERT_COMMIT=43d8b4f20ef743a7c5beb69c365538e726cc20d9
GEMMA_SHA256=985ec5778144730b80666a0f71f1e06038eb07dd1a3e941c6ab7f963eda00a8e
SRC="$PROJECT_ROOT/.deps/LiteRT-LM"
OUT="$PROJECT_ROOT/native/artifacts/arm64-v8a"
BAZEL="${BAZEL:-bazelisk}"
BUILD_ROOT="${NATIVE_BUILD_ROOT:-/tmp/inferdroid-bazel-$UID}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK with platforms/android-36}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 30.0.14904198 (r30-beta1)}"
rg -q '^Pkg.BaseRevision = 30.0.14904198$' "$ANDROID_NDK_HOME/source.properties" || {
    echo 'Expected the release-matched NDK 30.0.14904198 (r30-beta1).' >&2; exit 1;
}
command -v "$BAZEL" >/dev/null || { echo "Bazel missing: $BAZEL" >&2; exit 1; }
mkdir -p "$PROJECT_ROOT/.deps"
if [ ! -d "$SRC/.git" ]; then
    GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 --branch v0.14.0-alpha.0 \
        https://github.com/google-ai-edge/LiteRT-LM.git "$SRC"
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$LITERTLM_COMMIT" ] || {
    echo 'LiteRT-LM checkout does not match the pinned commit.' >&2; exit 1;
}
rg -q "^LITERT_REF = \"$LITERT_COMMIT\"$" "$SRC/WORKSPACE"
[ "$(cat "$SRC/.bazelversion")" = 7.6.1 ]

# Only this LFS binary is a DT_NEEDED dependency of the proven text/NPU path.
# Its source implementation is not available at this upstream tag; no stub.
GEMMA="$SRC/prebuilt/android_arm64/libGemmaModelConstraintProvider.so"
if ! printf '%s  %s\n' "$GEMMA_SHA256" "$GEMMA" | sha256sum --check --status; then
    curl --fail --location --retry 3 \
        "https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/$LITERTLM_COMMIT/prebuilt/android_arm64/libGemmaModelConstraintProvider.so" \
        -o "$GEMMA.download"
    printf '%s  %s\n' "$GEMMA_SHA256" "$GEMMA.download" | sha256sum --check
    mv "$GEMMA.download" "$GEMMA"
fi
mkdir -p "$SRC/inferdroid"
cp "$PROJECT_ROOT/native/BUILD.bazel" "$SRC/inferdroid/BUILD.bazel"
cp "$PROJECT_ROOT/native/runtime_adapter.cc" "$PROJECT_ROOT/native/runtime_api.h" "$SRC/inferdroid/"

cd "$SRC"
[ "$("$BAZEL" --version)" = 'bazel 7.6.1' ] || {
    echo 'The actual Bazel executable must be 7.6.1.' >&2; exit 1;
}
# A Linux eCryptfs home has a 143-byte filename limit, below Bazel's generated
# solib directory names. Keep the build/cache tree on a normal filesystem.
"$BAZEL" --output_user_root="$BUILD_ROOT" build \
    --config=android_arm64 --action_env=HOME="$HOME" \
    --jobs="${NATIVE_JOBS:-6}" --local_ram_resources="${NATIVE_RAM_MB:-12000}" \
    //inferdroid:libinferdroid_litertlm.so \
    @litert//litert/vendors/google_tensor/dispatch:dispatch_api_so

mkdir -p "$OUT" "$PROJECT_ROOT/app/src/main/assets/third_party"
cp bazel-bin/inferdroid/libinferdroid_litertlm.so "$OUT/"
cp bazel-bin/external/litert/litert/vendors/google_tensor/dispatch/libLiteRtDispatch_GoogleTensor.so "$OUT/"
cp "$GEMMA" "$OUT/"
cp LICENSE "$PROJECT_ROOT/app/src/main/assets/third_party/LiteRT-LM-LICENSE.txt"
OUTPUT_BASE="$("$BAZEL" --output_user_root="$BUILD_ROOT" info output_base)"
cp "$OUTPUT_BASE/external/litert/LICENSE" "$PROJECT_ROOT/app/src/main/assets/third_party/LiteRT-LICENSE.txt"
python3 "$PROJECT_ROOT/scripts/collect-native-licenses.py" "$OUTPUT_BASE/external" \
    "$PROJECT_ROOT/app/src/main/assets/third_party/generated/LICENSES.txt"
cat > "$OUT/VERSIONS.txt" <<EOF
LiteRT-LM v0.14.0-alpha.0 $LITERTLM_COMMIT
LiteRT $LITERT_COMMIT
Bazel 7.6.1
NDK 30.0.14904198 (r30-beta1)
Target android_arm64 / arm64-v8a
Inference adapter SHA-256 $(sha256sum "$PROJECT_ROOT/native/runtime_adapter.cc" | cut -d ' ' -f 1)
EOF
sha256sum "$OUT"/*.so > "$OUT/SHA256SUMS"
echo "Native runtime ready in $OUT"
