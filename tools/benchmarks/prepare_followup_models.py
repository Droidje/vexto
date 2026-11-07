"""Pinned smaller multilingual Whisper models for comparison only; does not change the APK manifest."""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

REVISION = '5359861c739e955e79d9a303bcbc70fb988958b1'
MODELS = {
    'base': (59707625, '422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898'),
    'tiny': (32152673, '818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7'),
}


def main():
    root = Path(sys.argv[1]); root.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for name, (size, expected) in MODELS.items():
        target = root / f'ggml-{name}-q5_1.bin'
        url = f'https://huggingface.co/ggerganov/whisper.cpp/resolve/{REVISION}/{target.name}'
        if target.exists(): raise FileExistsError(target)
        with urllib.request.urlopen(url, timeout=120) as source, target.open('wb') as output:
            while block := source.read(1024 * 1024): output.write(block)
        with target.open('rb') as stream: checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
        if target.stat().st_size != size or checksum != expected: raise ValueError('Pinned model mismatch: ' + name)
        manifest[name] = dict(path=target.name, bytes=size, sha256=checksum, url=url, license='MIT')
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__': main()
