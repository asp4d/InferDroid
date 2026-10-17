#!/usr/bin/env python3
"""Prepare the pinned image runtime and tokenizer for normal InferDroid builds."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "native/image-runtime-lock.json"
CACHE = ROOT / ".deps/images"
RESEARCH = ROOT / ".local/tensor-g5"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use only verified local downloads.")
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    version = lock["litert_version"]
    existing = RESEARCH / ("cache/device-runtime-" + version + "-r2")
    model = RESEARCH / "models/sdxs-512-dreamshaper"

    def fetch(relative, url, expected, previous):
        destination = CACHE / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.is_file():
            temporary = destination.with_name(destination.name + ".part")
            if previous.is_file() and digest(previous) == expected:
                shutil.copyfile(previous, temporary)
            elif args.offline:
                raise RuntimeError("Missing pinned input: " + relative)
            else:
                with urlopen(url, timeout=60) as response, temporary.open("wb") as output:
                    shutil.copyfileobj(response, output)
            if digest(temporary) != expected:
                temporary.unlink()
                raise RuntimeError("Image input checksum mismatch: " + relative)
            temporary.replace(destination)
        if digest(destination) != expected:
            raise RuntimeError("Image input checksum mismatch: " + relative)
        return destination

    runtime = ROOT / "native/artifacts/images"
    libraries = runtime / "arm64-v8a"
    assets = ROOT / "app/src/main/assets/image-generation/generated"
    notices = ROOT / "app/src/main/assets/third_party/generated/images"
    for directory in [libraries, assets, notices]:
        directory.mkdir(parents=True, exist_ok=True)
    outputs = []

    def stage(source, destination):
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        outputs.append(destination)

    mapping = {
        "litert": ("libLiteRt.so", "libinferdroid_image_litert.so"),
        "litert-npu-runtime-google-tensor": ("libLiteRtDispatch_GoogleTensor.so", "libinferdroid_image_dispatch.so"),
    }
    for record in lock["archives"]:
        filename = record["url"].rsplit("/", 1)[1]
        archive = fetch(filename, record["url"], record["sha256"], existing / filename)
        artifact = filename.removesuffix("-" + version + ".aar")
        source, renamed = mapping[artifact]
        with zipfile.ZipFile(archive) as bundle:
            target = libraries / renamed
            target.write_bytes(bundle.read("jni/arm64-v8a/" + source))
            outputs.append(target)
            for name in ["LICENSE", "THIRD_PARTY_NOTICE.txt"]:
                target = notices / (artifact + "-" + name)
                target.write_bytes(bundle.read(name))
                outputs.append(target)
    for relative, checksum in lock["headers"].items():
        remote = "litert/build_common/config/build_config_npu.h" if relative == "litert/build_common/build_config.h" else relative
        source = fetch("include/" + relative,
                       "https://raw.githubusercontent.com/google-ai-edge/LiteRT/v" + version + "/" + remote,
                       checksum, existing / "include" / relative)
        stage(source, runtime / "include" / relative)
    model_url = "https://huggingface.co/" + lock["model_repository"] + "/resolve/" + lock["model_revision"] + "/"
    for name, checksum in lock["tokenizer"].items():
        source = fetch("tokenizer/" + name, model_url + "tokenizer/" + name, checksum, model / "tokenizer" / name)
        stage(source, assets / name)
    source = fetch("model-card.md", model_url + "README.md", lock["model_card_sha256"], model / "README.md")
    stage(source, notices / "SDXS-MODEL-CARD.md")
    metadata = {
        "lock_sha256": digest(LOCK),
        "litert_version": version,
        "files": {str(path.relative_to(ROOT)): digest(path) for path in sorted(outputs)},
    }
    (runtime / "manifest.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print("Image runtime, tokenizer, and upstream notices are ready for normal Gradle builds.")


if __name__ == "__main__":
    main()
