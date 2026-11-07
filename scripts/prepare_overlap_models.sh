#!/usr/bin/env bash
# Reproducible model preparation in an isolated build environment, never at app runtime.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p "${1:?Provide a scratch directory}"
overlap_work="$(cd "$1" && pwd)"
python3 -m venv "$overlap_work/venv"
overlap_python="$overlap_work/venv/bin/python"
if [[ "$(uname -s)" == Linux ]]; then
    "$overlap_python" -m pip install torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/cpu
else
    "$overlap_python" -m pip install torch==2.5.1 torchaudio==2.5.1
fi
"$overlap_python" -m pip install onnx==1.17.0
"$overlap_python" scripts/export_overlap_model.py --pretrained-dir "$overlap_work/pretrained"
python3 scripts/verify_overlap_model.py
