"""Isolated CI: native contracts, small ASR comparison, and two offline APK smoke runs on 4GB Android."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid
import wave
import zipfile
from android_native_session import Adb
from probe_followup_models import qwen, whisper


def checksum(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--pack-root', type=Path, required=True)
    parser.add_argument('--extra-whisper', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true': raise RuntimeError('This driver is for a disposable CI AVD only')
    args.output.mkdir(parents=True, exist_ok=False)
    adb = Adb()
    if adb.text('emu', 'avd', 'name').splitlines()[0] != 'vexto-followup': raise RuntimeError('Wrong AVD namespace')
    ram = int(adb.text('shell', 'cat /proc/meminfo').splitlines()[0].split()[1])
    if not 3_500_000 < ram < 4_300_000: raise RuntimeError('Expected 4096MB Android')
    with zipfile.ZipFile(args.apk) as archive:
        if 'assets/vexto-models/embedded' in archive.namelist(): raise RuntimeError('Expected small CI APK with staged models')
        manifest = json.loads(archive.read('assets/quality/manifest.json'))
        native = args.output / 'native'; native.mkdir()
        for name in ('llama', 'whisper'):
            file = f'libvexto_{name}_exec.so'
            (native / file).write_bytes(archive.read('lib/x86_64/' + file))
    package = 'com.vexto.reflect'
    adb.run('install', '-r', str(args.apk), timeout=180)
    adb.text('shell', 'am', 'start', '-n', package + '/com.vexto.engine.eval.QualityEvalActivity', '--es', 'mode', 'native-help')
    help_path = f'/sdcard/Android/data/{package}/files/quality-eval/native-help.json'
    for _ in range(100):
        raw = adb.text('shell', 'cat ' + help_path, check=False)
        if raw:
            if any(value != 'executed_exit_0' for value in json.loads(raw).values()):
                raise RuntimeError('Packaged native help probe failed: ' + raw)
            break
        time.sleep(.2)
    else: raise RuntimeError('Native help probe did not finish')
    version = manifest['version']
    if not version.replace('-', '').isalnum(): raise ValueError('Invalid model version')
    remote_models = f'/sdcard/Android/data/{package}/files/quality-models/{version}'
    model_paths = {}
    for ident, artifact in manifest['artifacts'].items():
        local = args.pack_root / version / artifact['path']
        if local.stat().st_size != artifact['bytes'] or checksum(local) != artifact['sha256']:
            raise ValueError('Model checksum mismatch: ' + ident)
        target = remote_models + '/' + artifact['path']
        adb.text('shell', 'mkdir -p ' + shlex.quote(str(Path(target).parent)))
        adb.push(local, target); model_paths[ident] = target
    extra = json.loads((args.extra_whisper / 'manifest.json').read_text())
    remote = '/data/local/tmp/vexto-followup-' + uuid.uuid4().hex
    adb.text('shell', 'mkdir -p ' + remote)
    for file in native.glob('*.so'): adb.push(file, remote + '/' + file.name)
    adb.text('shell', 'chmod 700 ' + remote + '/*.so')
    models = {'small': model_paths['whisper_fast']}
    for name, artifact in extra.items():
        local = args.extra_whisper / artifact['path']
        if checksum(local) != artifact['sha256']: raise ValueError('Extra Whisper checksum mismatch')
        target = remote + '/' + artifact['path']; adb.push(local, target); models[name] = target
    adb.text('shell', 'cmd connectivity airplane-mode enable')
    adb.text('shell', 'svc wifi disable'); adb.text('shell', 'svc data disable')
    results = dict(ram_kib=ram, apk_sha256=checksum(args.apk), model_manifest=manifest,
                   scope='Disposable Android CI; models staged before offline execution; this is not the distribution APK',
                   native_binary_sha256={file.name: checksum(file) for file in native.glob('*.so')})
    failures = []
    for name, task in (
        ('qwen', lambda: qwen(adb, remote + '/libvexto_llama_exec.so', model_paths['qwen'], remote, args.output / 'qwen')),
        ('whisper', lambda: whisper(adb, remote + '/libvexto_whisper_exec.so', models, remote, args.output / 'whisper')),
    ):
        try: results[name] = task()
        except Exception as error:
            failures.append(name); results[name] = dict(error=str(error), type=type(error).__name__)
        (args.output / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    short = Path('testdata/audio/ru/rinco/rinco-ru-05.wav')
    original = next(Path('testdata/audio/ru/long-conversation').glob('*.ogg'))
    voiced = args.output / 'interview-75s.wav'
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-ss', '180', '-i', str(original), '-t', '75',
                    '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(voiced)], check=True)
    padded = args.output / 'streaming-functional-301s.wav'
    with wave.open(str(voiced)) as source:
        pcm = source.readframes(source.getnframes())
    with wave.open(str(padded), 'wb') as output:
        output.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        output.writeframes(pcm); output.writeframes(bytes(301 * 32000 - len(pcm)))
    results['streaming_fixture'] = dict(source_sha256=checksum(original), source_offset_seconds=180,
        voiced_seconds=len(pcm) / 32000, total_seconds=301, note='Tail silence exercises AUTO+streaming; not a long-file speed benchmark')
    for mode, source in (('QUALITY', short), ('AUTO', padded)):
        dest = args.output / ('apk-' + mode.lower())
        attempt = subprocess.run([sys.executable, 'tools/benchmarks/probe_quality_apk.py', '--serial', 'emulator-5554',
            '--input', str(source), '--output-dir', str(dest), '--processing-mode', mode], timeout=660)
        if attempt.returncode: failures.append('apk-' + mode.lower())
        if mode == 'AUTO' and not attempt.returncode:
            job = dest / json.loads((dest / 'probe.json').read_text())['job_id']
            snapshot = job / 'streaming-chunks.json'
            if not snapshot.is_file() or json.loads(snapshot.read_text()).get('aborted'):
                failures.append('streaming-not-confirmed')
    results['failures'] = failures
    (args.output / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    return bool(failures)


if __name__ == '__main__': raise SystemExit(main())
