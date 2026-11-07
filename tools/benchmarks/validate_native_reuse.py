"""Fail closed before a probe reuses native backends from a previous APK build."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))


def main():
    run_id = sys.argv[1]
    if not run_id.isdecimal():
        raise ValueError('Expected a numeric Actions run ID')
    repo = os.environ['GITHUB_REPOSITORY']
    run = api(f'repos/{repo}/actions/runs/{run_id}')
    if run['repository']['full_name'] != repo or run['path'] != '.github/workflows/ci.yml':
        raise ValueError('Native artifact must come from this repository and workflow')
    jobs = api(f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100')['jobs']
    if not any(j['name'] == 'Full Android APK (ARM64 and x86_64)' and j['conclusion'] == 'success' for j in jobs):
        raise ValueError('Previous native APK job did not succeed')
    sha = run['head_sha']
    # The small Nemotron CLI is rebuilt from this checkout; its ABI providers and
    # compiler flags must be byte-identical to the successful native build.
    paths = ['tools/native/sources.json', 'tools/native/CMakeLists.txt',
             'tools/native/patch_diar_threads.py', 'tools/native/GuardCli.cmake',
             'scripts/build_native_runtime.py', 'scripts/prepare_android_runtime.sh']
    for path in paths:
        previous = api(f'repos/{repo}/contents/{path}?ref={sha}')
        old = base64.b64decode(previous['content']).replace(b'\r\n', b'\n')
        current = Path(path).read_bytes().replace(b'\r\n', b'\n')
        if path == 'scripts/prepare_android_runtime.sh':
            # dev added the independent app punctuation asset preparation. It does
            # not build/change the Nemotron backend reused by this CLI-only probe.
            def backend_recipe(value):
                return [line.strip() for line in value.splitlines() if line.strip()
                        and not line.lstrip().startswith(b'#')
                        and line.strip() != b'python3 scripts/prepare_punctuation_models.py']
            old, current = backend_recipe(old), backend_recipe(current)
        if old != current:
            raise ValueError(f'Native backend recipe changed: {path}; build a fresh APK')
    print(f'Validated native backends from run {run_id}, commit {sha}; frontend rebuilt separately.')


if __name__ == '__main__':
    main()
