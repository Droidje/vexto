#!/usr/bin/env python3
"""Offline conversion of the pinned official Nemotron .nemo checkpoint to F32-linear GGUF.

Uses the unmodified pinned NVIDIA converter, not dequantization of a Q8 file.
F32 output intentionally retains the converter's single F16 Conv1D kernel.
No inference, network, environment installation or global package changes.
"""
from __future__ import annotations
import argparse
import gc
import hashlib
import json
import os
from pathlib import Path
import sys
import time

CHECKPOINT_SHA = "867c53f552998f772e5b5e5c082962ae85ee7ca5669c2bc17d7f615133d4e96d"
CONVERTER_PINS = {
    "conversion/diarization.py": "b246c039dd56fea63500f105d77be17eafbc73f9c79fb6c8cd6e420101ed2317",
    "conversion/asr.py": "cb4c8150e5e98868fb9a42f002dbaab277797afab29cf618a1bf642378e6f1da",
    "conversion/quantization.py": "203df33778a5e3f68d6a416ff626db9643d2c3a5d7b525dee50056ef1841bd15",
    "conversion/source.py": "7d9fcd101540ddad7937d311388cf9128f1114ded5463f04fc7706e29ff15c75",
}
F32_SHA = "3339eca67ba8c7bc4a67b68797b60f8956e3ba1a34d5f84b14a0157ca96185b2"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--nemo-source", type=Path, required=True)
    parser.add_argument("--deps", type=Path, action="append", default=[], help="isolated CPU torch/numpy/PyYAML dirs")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threads", type=int, choices=range(1, 5), default=2)
    parser.add_argument("--check-only", action="store_true", help="validate sources/imports without tensor loading")
    parser.add_argument("--precision", choices=("f32", "f16"), default="f32")
    args = parser.parse_args()
    source = args.nemo_source.resolve()
    checkpoint = args.checkpoint.resolve()
    if checkpoint.stat().st_size != 198676480 or sha256(checkpoint) != CHECKPOINT_SHA:
        raise ValueError("checkpoint differs from pinned original NVIDIA .nemo")
    for name, expected in CONVERTER_PINS.items():
        if sha256(source / name) != expected:
            raise ValueError("converter source differs: " + name)
    gguf_source = source / "llama.cpp/gguf-py"
    if not (gguf_source / "gguf/__init__.py").is_file():
        raise ValueError("pinned llama.cpp gguf-py source is missing")
    sys.path[:0] = [str(path.resolve()) for path in args.deps] + [str(gguf_source), str(source)]
    os.environ.update(HF_HUB_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1", TRANSFORMERS_OFFLINE="1")
    for key in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS"):
        os.environ[key] = str(args.threads)
    import torch
    import numpy
    import yaml
    import gguf
    from conversion import diarization
    torch.set_num_threads(args.threads)
    versions = {"torch": torch.__version__, "numpy": numpy.__version__, "PyYAML": yaml.__version__}
    if args.check_only:
        print(json.dumps({"status": "validated_no_conversion_or_inference", "versions": versions}))
        return 0
    output = args.output.resolve()
    partial = output.with_suffix(output.suffix + ".converting")
    if output.exists() or partial.exists():
        raise FileExistsError("refusing existing conversion output or partial")
    output.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    diarization.convert(checkpoint, partial, args.precision)
    reader = gguf.GGUFReader(str(partial))
    tally: dict[str, int] = {}
    names = []
    for tensor in reader.tensors:
        tally[tensor.tensor_type.name] = tally.get(tensor.tensor_type.name, 0) + 1
        names.append(tensor.name)
    if len(names) != 360 or len(set(names)) != len(names) or (args.precision == "f32" and tally != {"F32": 359, "F16": 1}) or not set(tally) <= {"F32", "F16"}:
        raise ValueError("unexpected tensor names/types from pinned conversion")
    del tensor, reader
    gc.collect()  # release Windows mmap before atomic publication
    checksum = sha256(partial)
    if args.precision == "f32" and (checksum != F32_SHA or partial.stat().st_size != 395298848):
        raise ValueError("output differs from the verified F32-linear model; partial retained for inspection")
    partial.rename(output)
    manifest = {"status": "converted_not_inferred", "precision": args.precision, "tensor_types": tally,
                "filename": output.name, "size_bytes": output.stat().st_size, "sha256": checksum,
                "source_checkpoint_sha256": CHECKPOINT_SHA, "converter_pins": CONVERTER_PINS,
                "nemo_revision": "536b17a41bef62d2a67a40ed3df61df26a4389fe",
                "llama_revision": "bd4f514db14d87fded667787a7a963bfbaa98e89",
                "conversion_seconds": time.monotonic() - started, "versions": versions,
                "inference_performed": False}
    output.with_suffix(output.suffix + ".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
