#!/usr/bin/env bash
# Run in your own terminal. apksigner reads passwords directly from its console;
# this script never receives, stores, or prints them and never installs an app.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    printf 'Usage: bash scripts/sign-thinking-apk.sh "keystore path" [alias]\n' >&2
    exit 2
fi
if [[ ! -t 0 || ! -t 1 ]]; then
    printf 'Run this script in your own interactive terminal so the password stays private.\n' >&2
    exit 2
fi

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="$1"
ALIAS="${2:-key_asp4d}"
INPUT_APK="$ROOT/app/build/outputs/apk/thinking/inferdroid-debug.apk"
OUTPUT_APK="$ROOT/app/build/outputs/apk/thinking/inferdroid-signed.apk"
REFERENCE_APK="$ROOT/.deps/device-backup/inferdroid-installed-before-thinking.apk"
TOOLS="${ANDROID_HOME:-/opt/android-sdk}/build-tools/36.0.0"
SIGN_JAVA_HOME="${SIGN_JAVA_HOME:-/home/asp4d/.jdks/jbr-21.0.11}"
if [[ -x "$SIGN_JAVA_HOME/bin/java" ]]; then
    export JAVA_HOME="$SIGN_JAVA_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

for file in "$KEYSTORE" "$INPUT_APK" "$REFERENCE_APK" "$TOOLS/apksigner" "$TOOLS/zipalign"; do
    [[ -f "$file" ]] || { printf 'Missing file: %s\n' "$file" >&2; exit 1; }
done
if [[ -e "$OUTPUT_APK" ]]; then
    printf 'Already exists; preserving it: %s\n' "$OUTPUT_APK" >&2
    exit 1
fi

"$TOOLS/zipalign" -c -P 16 4 "$INPUT_APK"
# Existing debug signatures are replaced. No password arguments or variables.
"$TOOLS/apksigner" sign --alignment-preserved true --ks "$KEYSTORE" \
    --ks-key-alias "$ALIAS" --out "$OUTPUT_APK" "$INPUT_APK"
"$TOOLS/apksigner" verify --verbose "$OUTPUT_APK"
"$TOOLS/zipalign" -c -P 16 4 "$OUTPUT_APK"

certificate() {
    "$TOOLS/apksigner" verify --print-certs "$1" \
        | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
EXPECTED_CERT="$(certificate "$REFERENCE_APK")"
SIGNED_CERT="$(certificate "$OUTPUT_APK")"
printf '\nSigned APK: %s\nCertificate SHA-256: %s\n' "$OUTPUT_APK" "$SIGNED_CERT"
if [[ -z "$EXPECTED_CERT" || "$EXPECTED_CERT" != "$SIGNED_CERT" ]]; then
    printf 'This certificate differs from the installed app snapshot. No phone data was changed.\n' >&2
    printf 'Keep this signed APK and tell Codex the certificates did not match before proceeding.\n' >&2
    exit 1
fi
printf 'Certificate matches the installed app snapshot. Tell Codex signing is complete.\n'
