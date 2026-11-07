#!/usr/bin/env python3
"""Run a short offline Android APK probe and pull durable audio-to-output artifacts.

Models/APK must already be installed. Python3.12+, ADB, debug APK with QualityEvalActivity.
No inference is executed on this computer. Output directory must be new.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import time
import uuid

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',default='adb')
    parser.add_argument('--serial',required=True)
    parser.add_argument('--package',default='com.vexto.reflect')
    parser.add_argument('--input',type=Path,required=True)
    parser.add_argument('--output-dir',type=Path,required=True)
    parser.add_argument('--processing-mode', choices=('AUTO', 'FAST', 'QUALITY'), default='QUALITY')
    args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+',args.package):
        parser.error('Invalid application ID')
    if not args.input.is_file() or not re.fullmatch(r'\.[a-z0-9]{1,8}',args.input.suffix.lower()):
        parser.error('Input must be a local audio file with a simple extension')
    if args.output_dir.exists():
        parser.error('Use a new output directory; existing evidence is never replaced')
    args.output_dir.mkdir(parents=True)
    def adb(*argv: str,check: bool=True) -> bytes:
        result=subprocess.run([args.adb,'-s',args.serial,*argv],capture_output=True,timeout=45)
        if check and result.returncode:
            raise RuntimeError(f'ADB failed: {result.stderr.decode(errors="replace").strip()}')
        return result.stdout
    def text(*argv: str,check: bool=True) -> str:
        return adb(*argv,check=check).decode('utf-8',errors='replace').strip()
    def save(name: str,value: object) -> None:
        (args.output_dir/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    package_path=text('shell','pm','path',args.package).removeprefix('package:')
    if not re.fullmatch(r'/data/app/[A-Za-z0-9_/=.+~\-]+\.apk',package_path):
        raise ValueError('Expected one installed base APK, not split/missing package')
    with args.input.open('rb') as source:
        source_hash=hashlib.file_digest(source,'sha256').hexdigest()
    save('provenance.json',{'apk_sha256':text('shell','sha256sum',package_path).split()[0],
        'input_sha256':source_hash,'input_bytes':args.input.stat().st_size,
        'avd':text('emu','avd','name').splitlines()[0],
        'memory_kib':int(text('shell','cat','/proc/meminfo').splitlines()[0].split()[1]),
        'runtime':'Android APK; host only transfers and verifies artifacts', 'processing_mode': args.processing_mode})
    network={key:text('shell','settings','get','global',key) for key in ('airplane_mode_on','wifi_on','mobile_data')}
    save('network-before.json',network)
    run='probe-'+uuid.uuid4().hex
    base=f'/sdcard/Android/data/{args.package}/files/quality-eval/{run}'
    try:
        text('shell','cmd','connectivity','airplane-mode','enable')
        text('shell','svc','wifi','disable')
        text('shell','svc','data','disable')
        offline={'airplane_mode_on':text('shell','settings','get','global','airplane_mode_on'),
                 'wifi_status':text('shell','cmd','wifi','status').splitlines()[0]}
        save('network-during.json',offline)
        if offline['airplane_mode_on']!='1' or 'disabled' not in offline['wifi_status'].lower():
            raise RuntimeError('Emulator network did not enter offline state')
        text('shell','mkdir','-p',base)
        name='input'+args.input.suffix.lower()
        text('push',str(args.input),base+'/'+name)
        text('shell','am','start','-n',args.package+'/com.vexto.reflect.MainActivity')
        time.sleep(2)
        text('shell','am','start','-n',args.package+'/com.vexto.engine.eval.QualityEvalActivity',
             '--es','run',run,'--es','input',name,'--es','processing_mode',args.processing_mode)
        started=time.monotonic();previous=None
        while time.monotonic()-started < 600:
            error=text('shell','cat',base+'/error.txt',check=False)
            if error:
                (args.output_dir/'error.txt').write_text(error,encoding='utf-8')
                raise RuntimeError('APK probe failed; see error.txt')
            progress=text('shell','cat',base+'/progress.json',check=False)
            if progress and progress!=previous:
                print(f'{time.monotonic()-started:.1f}s {progress}',flush=True);previous=progress
            raw=text('shell','cat',base+'/done.json',check=False)
            if raw:
                done=json.loads(raw);save('done.json',done)
                job=Path(done['job']).name
                if not re.fullmatch(r'[0-9a-f-]{36}',job):
                    raise ValueError('Invalid private job identifier')
                payload=adb('exec-out','run-as',args.package,'tar','-C','files/inference/jobs','-cf','-',job)
                with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
                    archive.extractall(args.output_dir,filter='data')
                save('probe.json',{'job_id':job,'observed_wall_seconds':time.monotonic()-started,'run':run})
                from verify_quality_job import verify_job
                verification = verify_job(args.output_dir / job)
                save('verification.json', verification)
                print('Artifacts:',args.output_dir/job,flush=True)
                print('quality_success:',verification['quality_success'],flush=True)
                return 0 if verification['quality_success'] else 2
            time.sleep(2)
        raise TimeoutError('Short APK probe exceeded ten minutes; durable job is retained')
    finally:
        text('shell','cmd','connectivity','airplane-mode','enable' if network['airplane_mode_on']=='1' else 'disable',check=False)
        text('shell','svc','wifi','enable' if network['wifi_on']=='1' else 'disable',check=False)
        if network['mobile_data'] in ('0','1'):
            text('shell','svc','data','enable' if network['mobile_data']=='1' else 'disable',check=False)

if __name__=='__main__':
    raise SystemExit(main())
