#!/usr/bin/env python3
"""Fetch the public LiteRT 2.3.0 Android runtime and matching C API headers."""

import argparse
from collections import deque
import hashlib
import json
from pathlib import Path
import re
from urllib.request import urlopen
import zipfile

from private_io import prepare_output, run_private, workspace_root

VERSION = "2.3.0"
HEADER_ROOT = "https://raw.githubusercontent.com/google-ai-edge/LiteRT/v" + VERSION + "/"
MAVEN = "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        output = prepare_output(workspace_root(__file__), args.output_dir)
    except ValueError as error:
        parser.error(str(error))

    def prepare():
        native = output / "arm64-v8a"
        native.mkdir()
        records = []
        for artifact in ("litert", "litert-npu-runtime-google-tensor"):
            filename = artifact + "-" + VERSION + ".aar"
            url = MAVEN + artifact + "/" + VERSION + "/" + filename
            with urlopen(url, timeout=60) as response:
                data = response.read()
            path = output / filename
            path.write_bytes(data)
            records.append({"url": url, "sha256": hashlib.sha256(data).hexdigest()})
            with zipfile.ZipFile(path) as archive:
                for name in archive.namelist():
                    if name.startswith("jni/arm64-v8a/") and name.endswith(".so"):
                        (native / Path(name).name).write_bytes(archive.read(name))
        pending = deque("litert/c/" + name for name in (
            "litert_compiled_model.h", "litert_model.h", "litert_environment.h",
            "litert_options.h", "litert_tensor_buffer.h", "litert_tensor_buffer_requirements.h",
        ))
        seen = set()
        while pending:
            relative = pending.popleft()
            if relative in seen:
                continue
            if not relative.startswith("litert/") or ".." in Path(relative).parts:
                raise ValueError("Unexpected header path.")
            # Bazel supplies this generated header from a checked-in feature
            # configuration. Use the official NPU configuration for this probe.
            remote = ("litert/build_common/config/build_config_npu.h"
                      if relative == "litert/build_common/build_config.h" else relative)
            with urlopen(HEADER_ROOT + remote, timeout=60) as response:
                data = response.read()
            header = output / "include" / relative
            header.parent.mkdir(parents=True, exist_ok=True)
            header.write_bytes(data)
            seen.add(relative)
            pending.extend(re.findall(r'#include\s+"(litert/[^\"]+)"', data.decode()))
        (output / "source-record.json").write_text(json.dumps({
            "litert_version": VERSION, "artifacts": records,
            "headers": sorted(seen),
            "native_libraries": sorted(path.name for path in native.iterdir()),
        }, indent=2) + "\n")
        return 0

    return run_private(output, prepare)


if __name__ == "__main__":
    raise SystemExit(main())
