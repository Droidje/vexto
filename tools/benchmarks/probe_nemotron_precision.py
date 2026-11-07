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
    parser.add_argument('--prepare-native-only', action='store_true')
    parser.add_argument('--prepared-native', action='store_true')
    parser.add_argument('--precision', choices=('f32', 'f16', 'q8_0'), action='append')
    parser.add_argument('--compare-threads', action='store_true')
    parser.add_argument('--natural-clip', action='store_true')
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    lib = out / 'native'; lib.mkdir(exist_ok=True)
    stamp = lib / 'frontend.sha256'
    frontend_hash = hashlib.sha256(Path('tools/native/nemotron_main.cpp').read_bytes()).hexdigest()
    if args.prepared_native:
        if stamp.read_text().strip() != frontend_hash:
            raise RuntimeError('Prepared native frontend differs from this checkout')
    else:
        with zipfile.ZipFile(args.apk) as apk:
            for name in apk.namelist():
                if name.startswith('lib/x86_64/') and name.endswith('.so'):
                    (lib / Path(name).name).write_bytes(apk.read(name))
        if args.rebuild_frontend:
            rebuild_frontend(lib, args.models)
            stamp.write_text(frontend_hash + '\n')
    if args.prepare_native_only:
        if not args.rebuild_frontend: raise ValueError('Prepare-only needs a current frontend rebuild')
        return
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
    for path in lib.glob('*.so'): adb('push', str(path), remote + '/' + path.name)
    adb('shell', 'chmod 700 ' + remote + '/*.so')
    precisions = args.precision or os.environ.get('VEXTO_PROBE_PRECISIONS', 'f32,f16,q8_0').split(',')
    if not precisions or any(p not in ('f32', 'f16', 'q8_0') for p in precisions):
        raise ValueError('Invalid precision selection')
    model_hashes = {}
    for precision in dict.fromkeys(precisions):
        path = args.models / ('Nemotron-3-Diarization.' + precision + '.gguf')
        with path.open('rb') as stream: model_hashes[precision] = hashlib.file_digest(stream, 'sha256').hexdigest()
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
    if args.natural_clip:
        original = next(Path('testdata/audio/ru/long-conversation').glob('*.ogg'))
        natural = out / 'natural-interview-180s-to-216s.wav'
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-ss', '180', '-i', str(original), '-t', '36',
                        '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(natural)], check=True)
        with original.open('rb') as stream: checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
        prepared.append((natural, None, checksum, False))
    required_failures = []
    def publish():
        (out / 'results.json').write_text(json.dumps({'emulator_ram_kib': total, 'avx2_available': avx,
            'scope': 'short public Russian audio, fixed-order cold processes; OS cache uncontrolled; not app RSS or full-pipeline RTF',
            'required_failures': required_failures, 'runs': runs}, indent=2) + '\n', encoding='utf-8')
    for wav, reference, source_sha, synthetic in prepared:
        with wave.open(str(wav)) as f: duration = f.getnframes() / f.getframerate()
        adb('push', str(wav), remote + '/' + wav.name)
        configurations = [(p, avx if p != 'f32' else False, 4) for p in dict.fromkeys(precisions)]
        if avx and 'f32' in precisions: configurations.insert(1, ('f32', True, 4))
        if args.compare_threads and duration > 30 and 'f32' in precisions:
            configurations.append(('f32', avx, 2))
        for precision, optimized, threads in configurations:
            label = f'{wav.stem}-{precision}-' + ('avx2' if optimized else 'baseline') + f'-t{threads}'
            binary = 'libvexto_nemotron' + ('_avx2' if optimized else '') + '_exec.so'
            arguments = [remote + '/' + binary, remote + '/Nemotron-3-Diarization.' + precision + '.gguf',
                         remote + '/' + wav.name, '--gpu', '-1', '--preset', 'v3-offline', '--rttm', 'recording',
                         '--threads', str(threads)]
            progress = remote + '/' + label + '.progress.rttm'
            if duration > 30:
                arguments += ['--progress', progress]
            # Android owns the deadline too: an adb-client timeout must not leave a native model running.
            command = 'LD_LIBRARY_PATH=' + remote + ' ' + shlex.join(['timeout', '-s', 'KILL', '45', *arguments])
            started = time.monotonic()
            try:
                attempt = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', command],
                                         text=True, capture_output=True, timeout=55)
            except subprocess.TimeoutExpired:
                runs.append(dict(fixture=wav.name, precision=precision, threads=threads,
                    status='transport_timeout', model_sha256=model_hashes[precision]))
                if precision == 'f32': required_failures.append(label)
                publish(); continue
            elapsed = time.monotonic() - started
            (out / (label + '.stderr')).write_text(attempt.stderr, encoding='utf-8')
            if attempt.returncode:
                status = 'timeout' if attempt.returncode in (124, 137) and elapsed >= 44 else 'native_error'
                runs.append(dict(fixture=wav.name, precision=precision, avx2=optimized, threads=threads,
                    status=status, exit_code=attempt.returncode, adb_process_wall_seconds=elapsed,
                    model_sha256=model_hashes[precision], synthetic=synthetic))
                if precision == 'f32': required_failures.append(label)
                publish(); print(label, status, flush=True); continue
            prediction = attempt.stdout
            (out / (label + '.rttm')).write_text(prediction, encoding='utf-8')
            hypothesis, _ = parse_rttm(prediction)
            parity = None
            if duration > 30:
                snapshot = adb('shell', 'cat ' + shlex.quote(progress))
                frontier = int(snapshot.splitlines()[0].removeprefix('# stable_ms=')) / 1000
                prefix, _ = parse_rttm(snapshot)
                parity = frontier > 0 and clipped(prefix, frontier) == clipped(hypothesis, frontier)
                if not parity and precision == 'f32': required_failures.append(label + ':prefix_changed')
                (out / (label + '.progress.rttm')).write_text(snapshot, encoding='utf-8')
            measured = score(reference, hypothesis, duration) if reference is not None else {'status': 'inferred_without_reference'}
            measured.update(fixture=wav.name, precision=precision, avx2=optimized,
                            threads=threads, model_sha256=model_hashes[precision],
                            speaker_count=len({row[2] for row in hypothesis}),
                            adb_process_wall_seconds=elapsed, audio_seconds=duration,
                            source_fixture_sha256=source_sha, stable_prefix_parity=parity, synthetic=synthetic,
                            pcm_sha256=hashlib.sha256(wav.read_bytes()).hexdigest())
            runs.append(measured)
            publish()
            print(label, 'DER', measured.get('der_percent', 'no reference'), 'seconds', round(elapsed, 3), flush=True)
    # Never auto-promote precision based on these small utterance-envelope references.
    print('Precision probe finished; F32 remains the application default.')
    return 1 if required_failures else 0


if __name__ == '__main__': raise SystemExit(main())
