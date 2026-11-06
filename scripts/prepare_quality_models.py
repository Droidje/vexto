#!/usr/bin/env python3
"""Prepare a SHA256-verified offline quality pack; weights are never embedded in the APK.

--sources-json maps model IDs to existing files (keep it in .agents/local/ or .agents/work/).
--download fetches only pinned downloadable artifacts. Nemotron F32 must be converted
from the pinned official .nemo, not obtained by expanding a quantized Q8 file.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'engine/src/main/quality-assets/quality/manifest.json'

def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def contained(root: Path, relative: str) -> Path:
    file = (root / relative).resolve()
    if not file.is_relative_to(root.resolve()) or file == root.resolve():
        raise ValueError('Model path outside pack directory')
    return file

def valid(file: Path, artifact: dict) -> bool:
    return file.is_file() and file.stat().st_size == artifact['bytes'] and digest(file) == artifact['sha256']

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources-json', type=Path)
    parser.add_argument('--source-dir', type=Path, help='Existing pack root with manifest-relative filenames')
    parser.add_argument('--output-dir', type=Path, default=ROOT / '.agents/work/quality-pack')
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    sources = json.loads(args.sources_json.read_text(encoding='utf-8')) if args.sources_json else {}
    pack = args.output_dir.resolve() / manifest['version']
    for model_id, artifact in manifest['artifacts'].items():
        target = contained(pack, artifact['path'])
        if valid(target, artifact):
            print(f'{model_id}: verified')
            continue
        if args.verify_only:
            raise ValueError(f'Missing or invalid model: {model_id}')
        if target.exists():
            raise ValueError(f'Refusing to replace an invalid existing pack file: {target}')
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + '.part')
        if temporary.exists():
            raise ValueError(f'Remove or inspect interrupted pack artifact: {temporary}')
        source = Path(sources[model_id]) if model_id in sources else contained(args.source_dir, artifact['path']) if args.source_dir else None
        try:
            if source is not None:
                if not valid(source, artifact):
                    raise ValueError(f'Source SHA256 or byte count mismatch: {model_id}')
                try:
                    os.link(source, temporary)
                except OSError:
                    shutil.copyfile(source, temporary)
            elif args.download and artifact.get('url'):
                with urllib.request.urlopen(artifact['url'], timeout=90) as response, temporary.open('wb') as output:
                    shutil.copyfileobj(response, output, 1024 * 1024)
                    output.flush(); os.fsync(output.fileno())
            else:
                raise ValueError(f'Provide a verified local source for {model_id}; see engine/README.md')
            if not valid(temporary, artifact):
                raise ValueError(f'Download/copy SHA256 or size mismatch: {model_id}')
            temporary.rename(target)
        finally:
            if temporary.exists():
                temporary.unlink()
        print(f'{model_id}: prepared')
    pack.mkdir(parents=True, exist_ok=True)
    (pack / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'Offline pack: {pack}')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
