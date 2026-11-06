#!/usr/bin/env python3
"""Install an existing verified offline pack into one Android app's scoped storage.

The app must have been installed/launched. No APK/user recording/cache is removed.
ADB shell must be allowed to write this debug app's external files directory.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import subprocess
import uuid
from prepare_quality_models import MANIFEST, valid

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack-dir', type=Path, required=True, help='Versioned directory containing manifest.json')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.vexto.reflect')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+', args.package):
        parser.error('Invalid Android application ID')
    expected = json.loads(MANIFEST.read_text(encoding='utf-8'))
    manifest = json.loads((args.pack_dir / 'manifest.json').read_text(encoding='utf-8'))
    if manifest != expected:
        parser.error('Pack manifest differs from bundled quality profile')
    for artifact in manifest['artifacts'].values():
        if not valid(args.pack_dir / artifact['path'], artifact):
            parser.error('Pack contains missing/corrupt weights')
    def adb(*argv: str) -> str:
        result = subprocess.run([args.adb, '-s', args.serial, *argv], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError(f'ADB operation failed: {result.stderr.strip()} {result.stdout.strip()}')
        return result.stdout
    base = f'/sdcard/Android/data/{args.package}/files/quality-models/{manifest["version"]}'
    for model_id, artifact in manifest['artifacts'].items():
        relative = artifact['path']
        if not re.fullmatch(r'[A-Za-z0-9_.\-/]+', relative) or '..' in relative.split('/'):
            raise ValueError('Unsafe model path')
        target = base + '/' + relative
        try:
            if adb('shell', 'sha256sum', target).split()[0] == artifact['sha256']:
                print(f'{model_id}: already verified', flush=True)
                continue
        except (RuntimeError, IndexError):
            pass
        temporary = target + '.pending-' + uuid.uuid4().hex
        adb('shell', 'mkdir', '-p', target.rsplit('/', 1)[0])
        try:
            adb('push', str(args.pack_dir / relative), temporary)
            if adb('shell', 'sha256sum', temporary).split()[0] != artifact['sha256']:
                raise ValueError('Android weight SHA256 mismatch')
            adb('shell', 'mv', temporary, target)
        finally:
            adb('shell', 'rm', '-f', temporary)
        print(f'{model_id}: installed and verified', flush=True)
    print('Offline pack installed. Inference requires no network.')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
