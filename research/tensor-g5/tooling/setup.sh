#!/usr/bin/env bash
set -euo pipefail
umask 077

script_path="$(realpath -- "${BASH_SOURCE[0]}")"
tool_dir="$(dirname -- "$script_path")"
if [[ "${1:-}" == "--help" ]]; then
  printf 'Usage: bash setup.sh /absolute/path/to/approved/SDK-directory-or-compiler.tar.gz\n'
  printf 'Alternatively, set GOOGLE_TENSOR_SDK_BETA to the archive path.\n'
  exit 0
fi
if [[ "$#" -gt 1 ]]; then
  printf 'Expected one SDK directory or archive path. See --help.\n' >&2
  exit 2
fi
sdk_archive="${1:-${GOOGLE_TENSOR_SDK_BETA:-}}"
if [[ -z "$sdk_archive" || ( ! -f "$sdk_archive" && ! -d "$sdk_archive" ) ]]; then
  printf 'An approved Google Tensor SDK directory or compiler archive is required.\n' >&2
  exit 2
fi
sdk_archive="$(realpath -- "$sdk_archive")"
python_bin="${TENSOR_G5_PYTHON:-python3}"
workspace_dir="$("$python_bin" - "$tool_dir" "$sdk_archive" <<'PY'
import platform, sys
from pathlib import Path
if sys.platform != 'linux' or platform.machine() != 'x86_64':
    sys.exit('Linux x86_64 is required.')
if sys.version_info < (3, 11):
    sys.exit('Python 3.11+ is required; this toolkit was checked with Python 3.12.')
tool = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(tool))
from private_io import workspace_root
workspace = workspace_root(tool / 'setup.sh')
if not Path(sys.argv[2]).resolve().is_relative_to(workspace):
    print('The supplied SDK must be inside the private workspace: ' + str(workspace), file=sys.stderr)
    sys.exit(2)
print(workspace)
PY
)"
state_dir="$workspace_dir/tooling"
mkdir -p "$workspace_dir/tmp" "$workspace_dir/logs" "$workspace_dir/cache" "$state_dir"
private_tmp="$(mktemp -d "$workspace_dir/tmp/setup.XXXXXXXX")"
installation_log="$(mktemp "$workspace_dir/logs/setup.XXXXXXXX.log")"
export TMPDIR="$private_tmp"
export PIP_CACHE_DIR="$workspace_dir/cache/pip"
if [[ ! -d "$state_dir/.venv" ]]; then
  "$python_bin" -m venv "$state_dir/.venv"
fi
export GOOGLE_TENSOR_SDK_BETA="$sdk_archive"
export GOOGLE_TENSOR_BACKEND_ENABLED=1
aot_requirements="$tool_dir/requirements.txt"
if [[ -f "$tool_dir/requirements-lock.txt" ]]; then
  aot_requirements="$tool_dir/requirements-lock.txt"
fi
if ! "$state_dir/.venv/bin/python" -m pip install -r "$aot_requirements" > "$installation_log" 2>&1; then
  printf 'SDK setup failed. Inspect the private log locally: %s\n' "$installation_log" >&2
  exit 1
fi
# The public LiteRT backend supports an unpacked SDK directly through
# GOOGLE_TENSOR_COMPILER_LIB. No SDK files need to be copied into site-packages.
if [[ -f "$sdk_archive" ]]; then
  if ! "$state_dir/.venv/bin/python" -m pip install 'ai-edge-litert-sdk-google-tensor==2.3.0' >> "$installation_log" 2>&1; then
    printf 'SDK archive installation failed. Inspect the private log locally: %s\n' "$installation_log" >&2
    exit 1
  fi
fi
if ! "$state_dir/.venv/bin/python" - "$sdk_archive" "$state_dir/compiler-source.json" >> "$installation_log" 2>&1 <<'PY'
from datetime import datetime, timezone
import hashlib, json, sys
from importlib import metadata, util
from pathlib import Path
source = Path(sys.argv[1])
if source.is_dir():
    path = source
    if util.find_spec('ai_edge_litert_sdk_google_tensor') is not None:
        raise SystemExit('Use a fresh environment for direct SDK-directory mode; the archive installer takes precedence in LiteRT.')
else:
    import ai_edge_litert_sdk_google_tensor as sdk
    path = sdk.path_to_sdk_libs()
if path is None or not (Path(path) / 'liblitert_plugin_compiler.so').is_file():
    raise SystemExit('The SDK package did not install liblitert_plugin_compiler.so.')
with (Path(path) / 'liblitert_plugin_compiler.so').open('rb') as stream:
    compiler_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
record = {
    'installed_at_utc': datetime.now(timezone.utc).isoformat(),
    'source_kind': 'directory' if source.is_dir() else 'archive',
    'sdk_library_directory': str(Path(path).resolve()),
    'ai_edge_litert_version': metadata.version('ai-edge-litert'),
    'vendor_compiler_sha256': compiler_hash,
}
if source.is_file():
    with source.open('rb') as stream:
        record.update(archive_name=source.name, archive_sha256=hashlib.file_digest(stream, 'sha256').hexdigest())
Path(sys.argv[2]).write_text(json.dumps(record, indent=2) + '\n')
print('Vendor compiler directory:', path)
PY
then
  printf 'SDK validation failed. Inspect the private log locally: %s\n' "$installation_log" >&2
  exit 1
fi
printf 'SDK setup completed. Private installation log: %s\n' "$installation_log"
printf '\nRun the smoke test in README.md before compiling a model.\n'
