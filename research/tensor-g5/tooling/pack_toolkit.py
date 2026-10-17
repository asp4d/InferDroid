#!/usr/bin/env python3
"""Build a scripts-only private ZIP from an explicit file allowlist."""

import argparse
import hashlib
import os
from pathlib import Path
import zipfile

from private_io import private_path, workspace_root


FILES = (
    "README.md", "requirements.txt", "requirements-lock.txt",
    "requirements-export.txt", "requirements-export-lock.txt",
    "setup.sh", "setup-export.sh", "private_io.py", "compile.py",
    "download_sdxs.py", "export_sdxs.py", "generate_sdxs_reference.py",
    "prepare_device_runtime.py", "build_device_probe.py", "npu_probe.cc",
    "run_device_probe.py", "generate_on_pixel.py", "run_sdxs.py", "validate_app_outputs.py", "pack_toolkit.py",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    tool = Path(__file__).resolve().parent
    try:
        archive_path = private_path(workspace_root(__file__), args.archive)
    except ValueError as error:
        parser.error(str(error))
    if archive_path.exists() or archive_path.with_suffix(".zip.sha256").exists():
        parser.error("Use a new archive name; previous archives are preserved.")
    contents = {}
    for name in FILES:
        source = tool / name
        if source.is_symlink() or not source.is_file():
            parser.error("A required, regular toolkit file is missing: " + name)
        contents[name] = source.read_bytes()
    checksums = "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n"
                        for name, data in sorted(contents.items()))
    contents["FILES.sha256"] = checksums.encode()
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(contents.items()):
            entry = zipfile.ZipInfo("tensor-g5-aot-local/" + name)
            entry.external_attr = 0o100600 << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    with zipfile.ZipFile(archive_path) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("The new archive failed its integrity check.")
    with archive_path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    with archive_path.with_suffix(".zip.sha256").open("x") as stream:
        stream.write(digest + "  " + archive_path.name + "\n")
    print("Private scripts-only archive created and checked.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
