"""Measure prepared native models inside Android, without installing an APK.

See docs/research/android-models-4gb.md for assets, staging paths, and limits.
Python 3.10+ and ADB only; no host-side ML inference.
"""
import argparse
import datetime
import hashlib
import json
import re
import shlex
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--remote-root', default='/data/local/tmp/vexto-model-research')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--repeats', type=int, default=3, choices=range(1, 11))
    parser.add_argument('--include-llm', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'/data/local/tmp/[A-Za-z0-9_/-]+', args.remote_root):
        parser.error('remote-root must be a simple path below /data/local/tmp/')
    args.output_dir.mkdir(parents=True, exist_ok=True)

    def shell(command, timeout=120):
        return subprocess.run([args.adb, '-s', args.serial, 'shell', command],
                              capture_output=True, text=True, encoding='utf-8',
                              errors='replace', timeout=timeout)

    def read(command):
        result = shell(command)
        result.check_returncode()
        return result.stdout.strip()

    if read('getprop sys.boot_completed') != '1':
        raise RuntimeError('Android has not completed booting')
    cpuinfo = read('cat /proc/cpuinfo')
    environment = {
        'android_api': read('getprop ro.build.version.sdk'),
        'abi_list': read('getprop ro.product.cpu.abilist'),
        'online_cpus': read('getconf _NPROCESSORS_ONLN'),
        'cpu_model': next((x.split(':', 1)[1].strip() for x in cpuinfo.splitlines()
                           if x.startswith('model name')), 'unknown'),
        'meminfo_before': read('head -n 3 /proc/meminfo'),
        'heap_growth_limit': read('getprop dalvik.vm.heapgrowthlimit'),
        'java_max_heap': read('getprop dalvik.vm.heapsize'),
    }
    tasks = [
        ('silero-vad', 16.0, ['bin/sherpa-onnx-vad',
          '--silero-vad-model=silero_vad.onnx', 'two-speakers-en.wav', 'vad-output.wav']),
        ('pyannote-int8-campplus-auto', 16.0,
         ['bin/sherpa-onnx-offline-speaker-diarization',
          '--segmentation.pyannote-model=segmentation.int8.onnx',
          '--embedding.model=campplus.onnx', '--clustering.cluster-threshold=0.5',
          'two-speakers-en.wav']),
        ('gigaam-v2-int8', 11.29, ['bin/sherpa-onnx-offline',
          '--encoder=gigaam/encoder.int8.onnx', '--decoder=gigaam/decoder.onnx',
          '--joiner=gigaam/joiner.onnx', '--tokens=gigaam/tokens.txt',
          '--model-type=nemo_transducer', '--num-threads=2', 'gigaam/test_wavs/example.wav']),
        ('small-zipformer-ru-int8', 11.29, ['bin/sherpa-onnx-offline',
          '--encoder=zipformer/encoder.int8.onnx', '--decoder=zipformer/decoder.onnx',
          '--joiner=zipformer/joiner.int8.onnx', '--tokens=zipformer/tokens.txt',
          '--num-threads=2', 'gigaam/test_wavs/example.wav']),
    ]
    if args.include_llm:
        tasks.append(('qwen3-0.6b-q8-bench', None, ['llama/bin/llama-bench',
                      '-m', 'Qwen3-0.6B-Q8_0.gguf', '-p', '128', '-n', '32',
                      '-r', '1', '-t', '2', '-ngl', '0', '-o', 'json']))

    results = []
    for name, duration, command in tasks:
        for repeat in range(1, args.repeats + 1):
            remote_command = (f'cd {shlex.quote(args.remote_root)} && '
                              'LD_LIBRARY_PATH=lib:llama/lib toybox time -v '
                              + shlex.join(command))
            process = shell(remote_command, timeout=180)
            prefix = args.output_dir / f'{name}-{repeat}'
            Path(str(prefix) + '.stdout.log').write_bytes(process.stdout.encode('utf-8'))
            Path(str(prefix) + '.stderr.log').write_bytes(process.stderr.encode('utf-8'))
            process.check_returncode()
            combined = process.stdout + '\n' + process.stderr
            elapsed = re.search(r'Real time \(s\): ([\d.]+)', combined)
            rss = re.search(r'Max RSS \(KiB\): (\d+)', combined)
            if not elapsed or not rss:
                raise RuntimeError(f'Missing time/RSS measurements for {name}')
            item = {'model': name, 'repeat': repeat, 'exit_code': process.returncode,
                    'process_wall_seconds': float(elapsed[1]), 'peak_rss_kib': int(rss[1]),
                    'remote_command': remote_command,
                    'stdout_sha256': hashlib.sha256(process.stdout.encode()).hexdigest(),
                    'stderr_sha256': hashlib.sha256(process.stderr.encode()).hexdigest()}
            if duration is not None:
                item['audio_seconds'] = duration
                item['process_rtf'] = item['process_wall_seconds'] / duration
            decode = re.search(r'Elapsed seconds: ([\d.]+)', combined)
            if decode:
                item['inference_seconds_reported_by_runtime'] = float(decode[1])
            if name.endswith('auto'):
                segments = re.findall(r'([\d.]+) -- ([\d.]+) (speaker_\d+)', process.stdout)
                if not segments or len({x[2] for x in segments}) != 2:
                    raise RuntimeError('Two-speaker fixture did not yield two distinct labels')
                item['segments'] = [{'start_s': float(a), 'end_s': float(b), 'speaker': c}
                                    for a, b, c in segments]
                if any(not 0 <= float(a) < float(b) <= duration for a, b, _ in segments):
                    raise RuntimeError('Invalid diarization boundary')
            elif name.endswith('int8'):
                decoded = next((json.loads(line) for line in process.stdout.splitlines()
                                if line.startswith('{"lang"')), None)
                if not decoded or not decoded.get('text') or not decoded.get('timestamps'):
                    raise RuntimeError('ASR returned no text or timestamps')
                item['text'] = decoded['text']
                item['timestamp_count'] = len(decoded['timestamps'])
            elif name.endswith('bench'):
                item['llama_bench'] = json.loads(process.stdout)
            else:
                item['speech_regions'] = re.findall(r'([\d.]+) -- ([\d.]+)', combined)
                if not item['speech_regions']:
                    raise RuntimeError('VAD returned no speech')
            results.append(item)
            print(f'{name} #{repeat}: {item["process_wall_seconds"]:.3f}s, '
                  f'{item["peak_rss_kib"]/1024:.1f} MiB RSS', flush=True)
    report = {'created_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'environment': environment, 'runs': results,
              'limitations': ['Native CLI smoke tests, not an Android APK benchmark.',
                              'Separate processes; filesystem caches may be warm.',
                              'No WER/DER reference annotations or quality score.',
                              'No physical ARM phone or GPU/NPU performance measured.']}
    output = args.output_dir / 'measurements.json'
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(output, flush=True)


if __name__ == '__main__':
    main()
