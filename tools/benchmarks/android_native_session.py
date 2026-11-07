"""One bounded Android mailbox worker. Host Python only transfers requests and records measurements."""
import json
from pathlib import Path
import re
import shlex
import subprocess
import time


class Adb:
    def __init__(self, serial='emulator-5554'):
        self.prefix = ['adb', '-s', serial]

    def run(self, *args, check=True, timeout=60):
        result = subprocess.run([*self.prefix, *map(str, args)], capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError('ADB: ' + result.stderr.decode(errors='replace')[:2000])
        return result.stdout

    def text(self, *args, **kwargs):
        return self.run(*args, **kwargs).decode('utf-8', errors='replace').strip()

    def push(self, local, remote):
        self.run('push', str(local), remote, timeout=180)


class NativeSession:
    def __init__(self, adb, binary, args, remote, output):
        self.adb, self.binary, self.args, self.remote = adb, binary, args, remote
        self.output = Path(output); self.output.mkdir(parents=True, exist_ok=False)
        self.pid = None; self.process = None; self.streams = []
        self.sequence = 0; self.peak_kib = 0

    def __enter__(self):
        self.adb.text('shell', 'mkdir -p ' + shlex.quote(self.remote))
        command = 'export LD_LIBRARY_PATH=' + shlex.quote(str(Path(self.binary).parent)) + '; '
        command += 'echo $$ > ' + shlex.quote(self.remote + '/pid') + '; exec '
        command += shlex.join([self.binary, '--worker-dir', self.remote, *self.args])
        self.streams = [(self.output / name).open('wb') for name in ('worker.stdout', 'worker.stderr')]
        self.process = subprocess.Popen([*self.adb.prefix, 'shell', command],
                                        stdout=self.streams[0], stderr=self.streams[1])
        for _ in range(50):
            value = self.adb.text('shell', 'cat ' + self.remote + '/pid', check=False)
            if value.isdecimal(): self.pid = int(value); return self
            if self.process.poll() is not None: break
            time.sleep(.1)
        self.close()
        raise RuntimeError('Native worker did not start')

    def request(self, args, timeout=120):
        self.sequence += 1
        name = f'request-{self.sequence:03d}'
        remote_out, remote_err = self.remote + '/' + name + '.stdout', self.remote + '/' + name + '.stderr'
        file = self.output / (name + '.json')
        file.write_text(json.dumps(dict(args=args, stdout=remote_out, stderr=remote_err)))
        self.adb.push(file, self.remote + '/request.pending')
        self.adb.text('shell', 'mv ' + self.remote + '/request.pending ' + self.remote + '/request.json')
        started = time.monotonic()
        while time.monotonic() - started < timeout:
            status = self.adb.text('shell', f'cat /proc/{self.pid}/status', check=False)
            match = re.search(r'^VmHWM:\s+(\d+)', status, re.M)
            if match: self.peak_kib = max(self.peak_kib, int(match[1]))
            response = self.adb.text('shell', 'cat ' + self.remote + '/response.json', check=False)
            if response:
                result = json.loads(response)
                self.adb.text('shell', 'rm ' + self.remote + '/response.json')
                result.update(wall_seconds=time.monotonic() - started, pid=self.pid, native_process_peak_kib=self.peak_kib)
                if result['ok']:
                    result['stdout'] = self.adb.text('shell', 'cat ' + remote_out)
                (self.output / (name + '.response.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
                return result
            if self.process.poll() is not None: raise RuntimeError('Native worker exited; inspect worker.stderr')
            time.sleep(.15)
        self.close()
        raise TimeoutError('Bounded native mailbox request timed out')

    def close(self):
        if self.pid is not None:
            identity = self.adb.text('shell', f'cat /proc/{self.pid}/cmdline', check=False)
            if self.binary in identity and self.remote in identity:
                self.adb.text('shell', f'kill -KILL {self.pid}', check=False)
        if self.process is not None:
            try: self.process.wait(timeout=5)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait(timeout=5)
        for stream in self.streams: stream.close()

    def __exit__(self, *_): self.close()
