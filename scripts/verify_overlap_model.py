#!/usr/bin/env python3
"""Check the complete locally exported offline overlap pack before native packaging."""
import json
from export_overlap_model import ROOT, ENCODER_SHA, THRESHOLD, digest


def main():
    root = ROOT/'engine/src/main/overlap-assets'
    manifest = json.loads((root/'overlap/manifest.json').read_text())
    assert manifest['path'] == 'overlap/wavlm.onnx'
    assert manifest['sample_rate'] == 16000 and manifest['window_samples'] == 64000
    assert manifest['threshold'] == THRESHOLD
    assert manifest['encoder_sha256'] == ENCODER_SHA
    assert manifest['head_sha256'] == digest(ROOT/'tools/models/overlap-head.json')
    model = root/manifest['path']
    assert model.stat().st_size == manifest['bytes'] and digest(model) == manifest['sha256']
    assert digest(root/'overlap/NOTICE.txt') == digest(ROOT/'tools/model-notices/overlap-wavlm-torchaudio-MIT.txt')
    print('Verified complete offline overlap model')


if __name__ == '__main__':
    main()
