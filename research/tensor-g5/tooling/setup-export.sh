#!/usr/bin/env bash
set -euo pipefail
umask 077

script_path="$(realpath -- "${BASH_SOURCE[0]}")"
tool_dir="$(dirname -- "$script_path")"
python_bin="${TENSOR_G5_PYTHON:-python3}"
workspace_dir="$("$python_bin" - "$tool_dir" <<'PY'
import sys
from pathlib import Path
tool = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(tool))
from private_io import workspace_root
print(workspace_root(tool / 'setup-export.sh'))
PY
)"
state_dir="$workspace_dir/tooling"
mkdir -p "$workspace_dir/logs" "$workspace_dir/tmp" "$workspace_dir/cache" "$state_dir"
export TMPDIR="$(mktemp -d "$workspace_dir/tmp/export-setup.XXXXXXXX")"
export PIP_CACHE_DIR="$workspace_dir/cache/pip"
export HF_HOME="$workspace_dir/cache/huggingface"
export HF_HUB_DISABLE_TELEMETRY=1
installation_log="$(mktemp "$workspace_dir/logs/export-setup.XXXXXXXX.log")"
if [[ ! -d "$state_dir/.export-venv" ]]; then
  "$python_bin" -m venv "$state_dir/.export-venv"
fi
if ! "$state_dir/.export-venv/bin/python" -m pip install \
  'torch==2.11.0' 'torchvision==0.26.0' \
  --index-url https://download.pytorch.org/whl/cpu > "$installation_log" 2>&1; then
  printf 'CPU PyTorch setup failed. Inspect locally: %s\n' "$installation_log" >&2
  exit 1
fi
export_requirements="$tool_dir/requirements-export.txt"
if [[ -f "$tool_dir/requirements-export-lock.txt" ]]; then
  export_requirements="$tool_dir/requirements-export-lock.txt"
fi
if ! "$state_dir/.export-venv/bin/python" -m pip install \
  -r "$export_requirements" >> "$installation_log" 2>&1; then
  printf 'Exporter dependency setup failed. Inspect locally: %s\n' "$installation_log" >&2
  exit 1
fi
if ! "$state_dir/.export-venv/bin/python" -m pip check >> "$installation_log" 2>&1; then
  printf 'Exporter dependencies are inconsistent. Inspect locally: %s\n' "$installation_log" >&2
  exit 1
fi
"$state_dir/.export-venv/bin/python" -m pip freeze > "$state_dir/requirements-export-resolved.txt"
printf 'Exporter environment prepared. Private log: %s\n' "$installation_log"
