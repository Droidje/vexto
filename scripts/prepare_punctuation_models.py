#!/usr/bin/env python3
"""Prepare the pinned RUPunct assets embedded in every native Android build."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'engine/src/main/punctuation-assets/models/punctuation'
REVISION = 'c4a35be7e4a413cdfb08427db75b621b571569c9'
ARTIFACTS = {
    'rupunct_small_int8.onnx': (29259673, 'b105da023474d98aa13ba18953ae67b04b17bd0595034bc06030c17536893933'),
    'tokenizer.json': (2413439, '7ca617388c2092a3a84272025c52bbf3c6db0aee225c0351186295c0b5d3ddc6'),
}


def valid(path: Path, size: int, checksum: str) -> bool:
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == checksum


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, help='Reuse already downloaded, verified files')
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, (size, checksum) in ARTIFACTS.items():
        target = OUTPUT / name
        if valid(target, size, checksum):
            print(f'{name}: verified')
            continue
        if args.verify_only:
            raise ValueError(f'Missing or invalid RUPunct asset: {name}')
        temporary = target.with_suffix(target.suffix + '.pending')
        try:
            source = args.source_dir / name if args.source_dir else None
            if source is not None:
                if not valid(source, size, checksum):
                    raise ValueError(f'Invalid source RUPunct asset: {name}')
                shutil.copyfile(source, temporary)
            else:
                url = f'https://huggingface.co/ekhodzitsky/rupunct-small-onnx/resolve/{REVISION}/{name}'
                with urllib.request.urlopen(url, timeout=180) as response, temporary.open('wb') as stream:
                    shutil.copyfileobj(response, stream)
            if not valid(temporary, size, checksum):
                raise ValueError(f'Invalid downloaded RUPunct asset: {name}')
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
        print(f'{name}: prepared')


if __name__ == '__main__':
    main()
