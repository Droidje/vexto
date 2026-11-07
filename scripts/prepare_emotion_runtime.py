"""Fetch only pinned MIT ORT1.28.2 C API headers. No ORT binary or Java AAR.

The native emotion adapter dlopens the single runtime supplied by sherpa-onnx.
Run before Gradle externalNativeBuild; --check-only performs no network/write.
"""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import urllib.request

BASE = "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/include/onnxruntime/core/session/"
HEADERS = {
    "onnxruntime_c_api.h": "b69a133143c0da61782b2b02cc8a620d21ac139231fd211719776d10385135c1",
    "onnxruntime_error_code.h": "5ce3b054e798eced8d14f5b86e98692fd33470463f96194ce0700a2d53dd8721",
    "onnxruntime_ep_c_api.h": "db86df0f846b8d3bdbf429a5c76c5805293196575ca3c475878612591cd18e51",
    "ORT-LICENSE": "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c",
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    output = repo / "engine/src/main/cpp/include"
    for name, expected in HEADERS.items():
        path = output/name
        if path.exists():
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError(f"Unexpected modified header: {name}; refusing overwrite")
        elif args.check_only:
            raise FileNotFoundError(path)
        else:
            url = BASE+name if name != "ORT-LICENSE" else "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/LICENSE"
            with urllib.request.urlopen(url, timeout=30) as response:
                content = response.read()
            if hashlib.sha256(content).hexdigest() != expected:
                raise ValueError(f"Header checksum mismatch: {name}")
            output.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        print(f"verified {name} {expected}")

if __name__ == "__main__":
    main()
