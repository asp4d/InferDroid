#!/usr/bin/env python3
"""Build the local ARM64 correctness probe against the public LiteRT runtime."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from private_io import prepare_output, private_path, run_private, workspace_root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-dir", required=True, type=Path)
    parser.add_argument("--ndk-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    root = workspace_root(__file__)
    try:
        runtime = private_path(root, args.runtime_dir)
        output = prepare_output(root, args.output_dir)
    except ValueError as error:
        parser.error(str(error))
    compiler = args.ndk_dir.resolve() / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang++"
    if not compiler.is_file():
        parser.error("The selected Android NDK has no ARM64 API-31 C++ compiler.")

    def build():
        source = Path(__file__).resolve().parent / "npu_probe.cc"
        command = [str(compiler), "-std=c++17", "-O2", "-static-libstdc++",
                   "-I" + str(runtime / "include"), str(source),
                   "-L" + str(runtime / "arm64-v8a"), "-lLiteRt", "-llog", "-landroid", "-ldl",
                   "-Wl,-rpath,$ORIGIN", "-o", str(output / "npu_probe")]
        subprocess.run(command, check=True)
        with source.open("rb") as stream:
            source_hash = hashlib.file_digest(stream, "sha256").hexdigest()
        (output / "build-record.json").write_text(json.dumps({
            "source_sha256": source_hash, "ndk": str(args.ndk_dir.resolve()),
            "runtime_directory": str(runtime), "target": "aarch64-linux-android31",
        }, indent=2) + "\n")
        return 0

    return run_private(output, build)


if __name__ == "__main__":
    raise SystemExit(main())
