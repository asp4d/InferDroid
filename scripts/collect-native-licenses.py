#!/usr/bin/env python3
"""Retain upstream dependency notices in the APK, including build-only sources.

This deliberately includes a superset of build dependencies. It does not claim
to reconstruct notices for undisclosed internals of upstream binary-only libs.
"""
import os
from pathlib import Path
import sys

external, destination = map(Path, sys.argv[1:])
notices = []
for repository in sorted(external.iterdir()):
    if not repository.is_dir():
        continue
    for directory, subdirectories, files in os.walk(repository):
        relative = Path(directory).relative_to(repository)
        # Root notices and license directories, without scanning test fixtures
        # or copying a toolchain's unrelated installed software documentation.
        if len(relative.parts) >= 2:
            subdirectories[:] = []
        else:
            subdirectories[:] = [p for p in subdirectories if not p.startswith('.')]
        for name in sorted(files):
            if not name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE", "COPYRIGHT", "UNLICENSE", "PATENTS")):
                continue
            source = Path(directory) / name
            if source.is_file() and source.stat().st_size <= 1_000_000:
                notices.append((source.relative_to(external), source.read_text(errors="replace")))
destination.parent.mkdir(parents=True, exist_ok=True)
with destination.open("w") as output:
    output.write("Upstream native build dependency licenses/notices.\nIncludes build-only dependencies.\n\n")
    for name, notice in sorted(notices):
        output.write(f"\n===== {name} =====\n\n{notice}\n")
print(f"Packaged {len(notices)} upstream license/notice files in {destination}")
