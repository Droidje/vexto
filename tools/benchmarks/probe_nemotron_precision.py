"""Short public-fixture precision/SIMD probe on an isolated 4GB Android emulator, never app data."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
import wave
import zipfile
from score_diarization import parse_rttm, score


def rebuild_frontend(lib, models):
    ndk = Path(os.environ['ANDROID_HOME']) / 'ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64'
    compiler = ndk / 'bin/clang++'
    sources = list((models.resolve().parents[1] / 'nemotron').glob('*/include/nemo_speech/diar.h'))
    if len(sources) != 1:
        raise RuntimeError('Expected one pinned Nemotron source tree')
    for suffix in ('', '_avx2'):
        subprocess.run([str(compiler), '--target=x86_64-linux-android28',
            '--sysroot=' + str(ndk / 'sysroot'), '-std=c++17', '-O2', '-fPIE', '-pie', '-static-libstdc++',
            '-I' + str(sources[0].parents[1]), 'tools/native/nemotron_main.cpp', 'tools/native/parent_death.cpp',
            '-L' + str(lib), '-lnemo_speech_asr_c' + suffix, '-llog', '-Wl,-z,max-page-size=16384',
            '-o', str(lib / ('libvexto_nemotron' + suffix + '_exec.so'))], check=True)


def clipped(rows, frontier):
    return sorted((round(a * 1000), round(min(b, frontier) * 1000), s)
                  for a, b, s in rows if a < frontier)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rebuild-frontend', action='store_true')
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    def adb(*items, timeout=60):
        return subprocess.check_output(['adb', '-s', 'emulator-5554', *items], text=True, timeout=timeout)
    memory = adb('shell', 'cat /proc/meminfo')
    total = int(next(row.split()[1] for row in memory.splitlines() if row.startswith('MemTotal:')))
    if not 3_500_000 < total < 4_300_000: raise RuntimeError('Expected 4096MB emulator')
    cpu = adb('shell', 'cat /proc/cpuinfo')
    flags = set(cpu.replace(':', ' ').split())
    avx = {'avx', 'avx2', 'fma', 'f16c', 'sse4_2'} <= flags
    remote = '/data/local/tmp/vexto-precision-probe'
    adb('shell', 'mkdir -p ' + remote)
    lib = out / 'native'; lib.mkdir(exist_ok=True)
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if name.startswith('lib/x86_64/') and name.endswith('.so'):
                (lib / Path(name).name).write_bytes(apk.read(name))
    if args.rebuild_frontend:
        rebuild_frontend(lib, args.models)
    for path in lib.glob('*.so'): adb('push', str(path), remote + '/' + path.name)
    adb('shell', 'chmod 700 ' + remote + '/*.so')
    for precision in ('f32', 'f16', 'q8_0'):
        path = args.models / ('Nemotron-3-Diarization.' + precision + '.gguf')
        adb('push', str(path), remote + '/' + path.name)
    runs = []
    fixtures = sorted(Path('testdata/audio/ru/rinco').glob('*.wav'))
    prepared = []
    for original in fixtures:
        wav = out / original.name
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', str(original), '-ar', '16000',
                        '-ac', '1', '-c:a', 'pcm_s16le', str(wav)], check=True)
        with wave.open(str(wav)) as f: duration = f.getnframes() / f.getframerate()
        reference, _ = parse_rttm(original.with_suffix('.rttm').read_text(encoding='utf-8'))
        prepared.append((wav, reference, hashlib.sha256(original.read_bytes()).hexdigest(), False))
    # 6 repeats of the same public two-speaker clip cross the 30.4s cache boundary.
    # This is a synthetic continuity check, not a representative long conversation.
    source, reference, source_sha, _ = prepared[-1]
    with wave.open(str(source)) as f:
        pcm = f.readframes(f.getnframes())
    period = len(pcm) / 32000 + .25
    repeated = out / 'synthetic-cache-continuity.wav'
    with wave.open(str(repeated), 'wb') as f:
        f.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        f.writeframes((pcm + bytes(8000)) * 6)
    prepared.append((repeated, [(a + n * period, b + n * period, s)
                     for n in range(6) for a, b, s in reference], source_sha, True))
    for wav, reference, source_sha, synthetic in prepared:
        with wave.open(str(wav)) as f: duration = f.getnframes() / f.getframerate()
        adb('push', str(wav), remote + '/' + wav.name)
        configurations = [('f32', False), ('f16', avx), ('q8_0', avx)]
        if avx: configurations.insert(1, ('f32', True))
        for precision, optimized in configurations:
            label = f'{wav.stem}-{precision}-' + ('avx2' if optimized else 'baseline')
            binary = 'libvexto_nemotron' + ('_avx2' if optimized else '') + '_exec.so'
            arguments = [remote + '/' + binary, remote + '/Nemotron-3-Diarization.' + precision + '.gguf',
                         remote + '/' + wav.name, '--gpu', '-1', '--preset', 'v3-offline', '--rttm', 'recording']
            progress = remote + '/' + label + '.progress.rttm'
            if synthetic:
                arguments += ['--progress', progress]
            command = 'LD_LIBRARY_PATH=' + remote + ' VEXTO_NEMOTRON_THREADS=4 ' + shlex.join(arguments)
            started = time.monotonic()
            prediction = adb('shell', command, timeout=45)
            elapsed = time.monotonic() - started
            (out / (label + '.rttm')).write_text(prediction, encoding='utf-8')
            hypothesis, _ = parse_rttm(prediction)
            parity = None
            if synthetic:
                snapshot = adb('shell', 'cat ' + shlex.quote(progress))
                frontier = int(snapshot.splitlines()[0].removeprefix('# stable_ms=')) / 1000
                prefix, _ = parse_rttm(snapshot)
                parity = frontier > 0 and clipped(prefix, frontier) == clipped(hypothesis, frontier)
                if not parity: raise RuntimeError('Nemotron stable prefix differs from final RTTM: ' + label)
            measured = score(reference, hypothesis, duration)
            measured.update(fixture=wav.name, precision=precision, avx2=optimized,
                            adb_process_wall_seconds=elapsed, audio_seconds=duration,
                            source_fixture_sha256=source_sha, stable_prefix_parity=parity, synthetic=synthetic,
                            pcm_sha256=hashlib.sha256(wav.read_bytes()).hexdigest())
            runs.append(measured)
            (out / 'results.json').write_text(json.dumps({'emulator_ram_kib': total, 'avx2_available': avx,
                'scope': 'public Russian clips <=6s plus synthetic 37s cache check; cold process includes model load and ADB; not app RSS or full-pipeline RTF',
                'runs': runs}, indent=2) + '\n', encoding='utf-8')
            print(label, 'DER', measured['der_percent'], 'seconds', round(elapsed, 3), flush=True)
    # Never auto-promote precision based on these small utterance-envelope references.
    print('Precision probe finished; F32 remains the application default.')


if __name__ == '__main__': main()
