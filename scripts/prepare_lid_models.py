#!/usr/bin/env python3
"""Reproduce the pinned Whisper Tiny language-only ONNX pair, without inference."""
from __future__ import annotations

import argparse
import importlib.metadata
import json
from pathlib import Path
import sys
import time
import urllib.request

from prepare_quality_models import MANIFEST, ROOT, valid

SOURCE_ROOT = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c"
SOURCES = {
    "tiny-encoder.int8.onnx": {
        "bytes": 12937772,
        "sha256": "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
    },
    "tiny-decoder.int8.onnx": {
        "bytes": 89855401,
        "sha256": "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
    },
}


def verified_source(directory: Path, name: str, download: bool) -> Path:
    artifact = SOURCES[name]
    target = directory / name
    if valid(target, artifact):
        return target
    if target.exists() or not download:
        raise ValueError(f"Missing or invalid pinned LID source: {name}")
    directory.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(name + ".part")
    if temporary.exists():
        raise FileExistsError(f"Inspect interrupted LID download: {temporary}")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(f"{SOURCE_ROOT}/{name}", timeout=90) as source, temporary.open("wb") as output:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    output.write(block)
            if not valid(temporary, artifact):
                raise ValueError(f"LID source checksum or byte count differs: {name}")
            temporary.rename(target)
            return target
        except OSError:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
        finally:
            temporary.unlink(missing_ok=True)
    raise AssertionError("unreachable")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, required=True, help="Isolated source download cache")
    parser.add_argument("--output-dir", type=Path, required=True, help="The converted pack's lid directory")
    parser.add_argument("--source-dir", type=Path, help="Optional existing pinned Tiny pair; no downloads")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    artifacts = {key: manifest["artifacts"][key] for key in ("lid_encoder", "lid_decoder")}
    outputs = {key: args.output_dir / Path(artifact["path"]).name for key, artifact in artifacts.items()}
    if all(valid(outputs[key], artifact) for key, artifact in artifacts.items()):
        print("Pinned LID pair already verified")
        return 0
    if any(path.exists() for path in outputs.values()):
        raise FileExistsError("Refusing incomplete or invalid existing LID outputs")
    sources = args.source_dir or args.work_dir
    encoder = verified_source(sources, "tiny-encoder.int8.onnx", args.source_dir is None)
    decoder = verified_source(sources, "tiny-decoder.int8.onnx", args.source_dir is None)
    sys.path.insert(0, str(ROOT))
    from tools.benchmarks.distill_whisper_lid import prune_whisper_lid

    summary = prune_whisper_lid(encoder, decoder, args.output_dir)
    for key, artifact in artifacts.items():
        if not valid(outputs[key], artifact):
            raise ValueError(f"Converted {key} differs from the pinned pack SHA256 or size")
    versions = {name: importlib.metadata.version(name) for name in ("onnx", "numpy", "protobuf")}
    provenance = {"status": "converted_not_inferred", "source_revision": SOURCE_ROOT,
                  "sources": SOURCES, "versions": versions,
                  "outputs": {key: {"path": artifact["path"], "sha256": artifact["sha256"],
                                    "bytes": artifact["bytes"]} for key, artifact in artifacts.items()}}
    (args.output_dir / "lid-preparation.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"Pinned LID pair prepared and SHA256-verified: {summary['languages']} languages")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
