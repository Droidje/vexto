#!/usr/bin/env python3
"""Export the frozen natural-overlap detector, without retraining/calibration.

Requires torch==2.5.1, torchaudio==2.5.1 and onnx==1.17.0. Only the official
WavLM checkpoint is downloaded; the learned head is versioned in this repository.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENCODER_SHA = '136a3e720c04f2c77bf7a4dc6a3868b14d5a2c145a988114b733cb1a8428be98'
THRESHOLD = 0.9120559394359589


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pretrained-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT/'engine/src/main/overlap-assets/overlap')
    args = parser.parse_args()
    import torch
    import torchaudio
    import onnx
    torch.set_num_threads(2)
    assert torch.__version__.split('+')[0] == '2.5.1'
    assert torchaudio.__version__.split('+')[0] == '2.5.1'
    encoder = torchaudio.pipelines.WAVLM_BASE_PLUS.get_model(
        dl_kwargs={'model_dir': str(args.pretrained_dir), 'progress': False}).eval()
    assert digest(args.pretrained_dir/'wavlm_base_plus.pth') == ENCODER_SHA
    head_path = ROOT/'tools/models/overlap-head.json'
    head = json.loads(head_path.read_text())

    class Detector(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = encoder
            for name in ('center', 'scale', 'weight', 'bias'):
                self.register_buffer(name, torch.tensor(head[name], dtype=torch.float32))

        def forward(self, audio):
            layers, _ = self.encoder.extract_features(audio)
            features = torch.cat([v for x in (layers[5], layers[11])
                                  for v in (x.mean(1), x.std(1))], 1)
            return torch.sigmoid(torch.nn.functional.linear(
                (features-self.center)/self.scale, self.weight, self.bias)).reshape(1)

    model = Detector().eval()
    args.output.mkdir(parents=True, exist_ok=True)
    output = args.output/'wavlm.onnx'
    with torch.inference_mode():
        torch.onnx.export(model, torch.zeros(1, 64000), str(output),
                          input_names=['audio'], output_names=['score'],
                          opset_version=17, do_constant_folding=True, dynamo=False)
    onnx.checker.check_model(str(output))
    manifest = dict(version='natural-overlap-v1', path='overlap/wavlm.onnx',
                    sha256=digest(output), bytes=output.stat().st_size,
                    sample_rate=16000, window_samples=64000, threshold=THRESHOLD,
                    encoder_sha256=ENCODER_SHA, head_sha256=digest(head_path))
    (args.output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    shutil.copyfile(ROOT/'tools/model-notices/overlap-wavlm-torchaudio-MIT.txt', args.output/'NOTICE.txt')
    print(json.dumps(manifest), flush=True)


if __name__ == '__main__':
    main()
