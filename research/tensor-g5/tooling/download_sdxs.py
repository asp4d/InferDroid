#!/usr/bin/env python3
"""Download the pinned SDXS checkpoint into the local experiment."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

from private_io import prepare_output, private_path, run_private, workspace_root

REPO = "IDKiro/sdxs-512-dreamshaper"
REVISION = "76f720262bb051da75666b22c902a78c8e16c763"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--component", choices=("decoder", "all"), default="decoder")
    args = parser.parse_args()
    root = workspace_root(__file__)
    try:
        destination = private_path(root, args.model_dir)
        output = prepare_output(root, args.output_dir)
    except ValueError as error:
        parser.error(str(error))

    def download():
        from huggingface_hub import snapshot_download
        patterns = ["README*", "LICENSE*", "model_index.json", "vae/*"]
        if args.component == "all":
            patterns += ["unet/*", "text_encoder/*", "tokenizer/*", "scheduler/*"]
        destination.mkdir(parents=True, exist_ok=True)
        snapshot_download(
            repo_id=REPO, revision=REVISION, local_dir=str(destination),
            allow_patterns=patterns, token=False, max_workers=2,
        )
        files = []
        for path in sorted(destination.rglob("*")):
            relative = path.relative_to(destination)
            if not path.is_file() or ".cache" in relative.parts:
                continue
            private_path(root, path)
            with path.open("rb") as stream:
                checksum = hashlib.file_digest(stream, "sha256").hexdigest()
            files.append({"path": str(relative), "size_bytes": path.stat().st_size, "sha256": checksum})
        (output / "download-record.json").write_text(json.dumps({
            "downloaded_at_utc": datetime.now(timezone.utc).isoformat(),
            "repo_id": REPO, "revision": REVISION, "component": args.component,
            "files": files,
        }, indent=2) + "\n")
        return 0

    return run_private(output, download)


if __name__ == "__main__":
    raise SystemExit(main())
