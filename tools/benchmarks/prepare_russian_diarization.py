"""Download real Russian Pear Chats demo clips and manual TextGrid references.

Audio stays in the caller's local cache. No model, ADB, Git, or package install.
No open redistribution license was located; do not bundle cached audio in Git/ZIP.
Reference covers the three annotated primary participants, not a separate Listener.
"""
import argparse
from collections import Counter
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct
import sys
import urllib.request
import wave

BASE = 'https://multidiscourse.iling-ran.ru'
CORPUS_PAGE = 'https://multidiscourse.ru/corpus/?en=1'
GUIDE = 'https://multidiscourse.ru/data/ann/pears_vocal%20annotation_eng.pdf'
SESSION_STARTS = {'04':301, '22':345, '23':382}
ANNOTATION_SHA256 = {
    '04N':'708aed85f90d6d017163d2577d197b37de822c1677a92b5d7ebf7fbec83aca0f',
    '04C':'c075d59b78db5f5a716f788806d20f14e8aaf0212faabfba951c5a92d1abfcd8',
    '04R':'4b4cd48f666851452231d2f1c50ed7fe42880f703526d5db0790475f521232a0',
    '22N':'8bd3face60880a75be4356310e49b7b02348e042ba2a2c4b74a746f6d2c92497',
    '22C':'34cada029d3a5c3f4381908205f23a8e5465f05619858d8d92ebac001d417ea1',
    '22R':'9b18a87a470b2ac53fff47960379c86fb93cbe70aa380558f78aeff1704ccace',
    '23N':'0c5234f4788d4921c1ee21d84ffdcb70f42699ad08df6f97316d5c00fed1dd6f',
    '23C':'de8c826a5ca603ce547c1a2f51f1169ea8e8d005f74d8bf310afe82a5e921bf9',
    '23R':'a79f9aeb5a1243b3ac92c06a0473eabb5fb5ec476d7fd0b72dab6ad126a7791b',
}
PCM_SHA256 = {
    '04':'ab69d48643d67e97c53fa6fe64b16b3ad0a1d2bb750820317e28585d19f40d98',
    '22':'6768fd1cec8465b7462d3f2936b1bcbd45a62b2761c1d1e62906d4337a58d625',
    '23':'05f02055236b23152a08c725b75c684e493344eeedeb69d3831e92fcc06e8ec6',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url, first=None, last=None):
    headers = {'User-Agent':'VextoResearch/1.0 (public corpus research)'}
    if first is not None:
        headers['Range'] = f'bytes={first}-{last}'
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        content_range = response.headers.get('Content-Range')
        if first is not None:
            if response.status != 206 or not content_range or not content_range.startswith(f'bytes {first}-'):
                raise RuntimeError('Server must honor bounded Range; refusing full WAV download')
            payload = response.read(last-first+2)
            if len(payload) != last-first+1:
                raise RuntimeError('Incomplete/oversized PCM range')
        else:
            payload = response.read(4_000_001)
            if len(payload) > 4_000_000:
                raise RuntimeError('Annotation exceeds expected small-file budget')
        return payload, {'url':response.url, 'content_range':content_range,
                         'downloaded_bytes':len(payload), 'sha256':digest(payload)}


def parse_words(data):
    text = data.decode('utf-16' if data[:2] in (b'\xff\xfe', b'\xfe\xff') else 'utf-8-sig')
    tiers = re.split(r'(?m)^    item \[\d+\]:', text)[1:]
    matches = [tier for tier in tiers if re.search(r'name\s*=\s*"Words"', tier)]
    if len(matches) != 1:
        raise ValueError('Expected one lower-level Words IntervalTier')
    rows = re.findall(r'intervals \[\d+\]:\s*xmin\s*=\s*([\d.eE+-]+)\s*xmax\s*=\s*([\d.eE+-]+)\s*text\s*=\s*"((?:[^"]|"")*)"', matches[0])
    expected = int(re.search(r'intervals:\s*size\s*=\s*(\d+)', matches[0]).group(1))
    if len(rows) != expected:
        raise ValueError('TextGrid interval count mismatch')
    kept = []
    excluded = Counter()
    previous_end = 0.0
    for start, end, label in rows:
        start, end = float(start), float(end)
        if not (math.isfinite(start) and math.isfinite(end) and 0 <= start < end) or start < previous_end-1e-8:
            raise ValueError('Invalid/nonmonotonic TextGrid interval')
        previous_end = end
        label = label.replace('""','"').strip()
        if not label:
            excluded['silent'] += 1
        elif label.startswith('{'):
            excluded['explicit_nonverbal'] += 1
        elif label == '(ɥ)':
            excluded['inhalation'] += 1
        else:
            kept.append((start,end))
    return kept, {'tier':'Words', 'source_intervals':expected,
                  'kept_intervals':len(kept), 'excluded_counts':dict(excluded)}


def wav_format(header):
    if header[:4] != b'RIFF' or header[8:12] != b'WAVE':
        raise ValueError('Expected RIFF/WAV')
    pos, fmt = 12, None
    while pos+8 <= len(header):
        name, size = header[pos:pos+4], struct.unpack_from('<I',header,pos+4)[0]
        if name == b'fmt ':
            fmt = struct.unpack_from('<HHIIHH',header,pos+8)
        if name == b'data':
            if fmt is None:
                raise ValueError('No format chunk before data')
            return fmt, pos+8
        pos += 8+size+size%2
    raise ValueError('WAV header missing data in bounded prefix')


def prepare(root, session, miniaudio):
    start, duration = SESSION_STARTS[session], 30
    sources, segments = [], []
    for role in 'NCR':
        name = f'pears{session}{role}-voc.TextGrid'
        path = root/name
        url = f'{BASE}/pears{session}/{name}'
        if path.exists():
            data = path.read_bytes()
            transport = {'url':url, 'cached':True, 'sha256':digest(data)}
        else:
            data, transport = fetch(url)
            path.write_bytes(data)
        if digest(data) != ANNOTATION_SHA256[session+role]:
            raise ValueError(f'Annotation differs from pinned research source: {name}')
        words, audit = parse_words(data)
        sources.append(dict(transport, participant=role, parsing=audit))
        for lo, hi in words:
            a, b = max(start,lo), min(start+duration,hi)
            if b > a:
                segments.append((a-start,b-start,role))
    times = {role:sum(b-a for a,b,speaker in segments if speaker==role) for role in 'NCR'}
    if min(times.values()) < 2:
        raise ValueError('Preselected clip no longer meets >=2 reference seconds per primary role')
    url = f'{BASE}/pears{session}/Pears{session}W-au.wav'
    header, header_source = fetch(url,0,65535)
    (encoding,channels,rate,byte_rate,align,bits), data_offset = wav_format(header)
    if (encoding,channels,rate,bits) != (1,2,48000,16):
        raise ValueError(f'Unexpected shared-microphone format: {(encoding,channels,rate,bits)}')
    first = data_offset+int(start*rate)*align
    last = first+duration*rate*align-1
    pcm, audio_source = fetch(url,first,last)
    if digest(pcm) != PCM_SHA256[session]:
        raise ValueError('Audio PCM range differs from pinned research source')
    buffer = io.BytesIO()
    with wave.open(buffer,'wb') as stream:
        stream.setnchannels(channels); stream.setsampwidth(2); stream.setframerate(rate); stream.writeframes(pcm)
    decoded = miniaudio.decode(buffer.getvalue(),output_format=miniaudio.SampleFormat.SIGNED16,
                               nchannels=1,sample_rate=16000)
    fixture_id = f'pears{session}-{start}-{start+duration}'
    output = root/f'{fixture_id}.wav'
    with wave.open(str(output),'wb') as stream:
        stream.setnchannels(1); stream.setsampwidth(2); stream.setframerate(16000); stream.writeframes(decoded.samples.tobytes())
    reference = root/f'{fixture_id}.rttm'
    lines = [f'SPEAKER {fixture_id} 1 {a:.9f} {b-a:.9f} <NA> <NA> {role} <NA> <NA>'
             for a,b,role in sorted(segments)]
    reference.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    return {'id':fixture_id,'wav':str(output.resolve()),'rttm':str(reference.resolve()),
            'duration_seconds':duration,'language':'ru','sample_rate':16000,'channels':1,
            'audio_sha256':digest(output.read_bytes()),'rttm_sha256':digest(reference.read_bytes()),
            'source_offset_seconds':start,'source_end_seconds':start+duration,
            'source_page':CORPUS_PAGE,'annotation_guide':GUIDE,
            'title':f'Russian Pear Chats and Stories, real shared-room conversation, session {session}',
            'attribution':'Russian Multichannel Discourse research group, Institute of Linguistics, Russian Academy of Sciences',
            'license':'No explicit open redistribution license located; publicly offered research demo; local research cache only',
            'redistribute_audio':False,'synthetic':False,'selection_before_inference':True,
            'selection_rule':'Earliest 1s-grid 30s window within first600s with >=2s retained Words activity for each N/C/R; fixed offsets, no model output used.',
            'reference_protocol':'Manual Words tier: words and filled pauses; empty intervals, explicit {nonverbal} and (ɥ) inhalations excluded. No pause filling or collar; overlaps retained.',
            'reference_speaker_ids':['N','C','R'],'reference_speaker_seconds':times,'reference_segments':len(segments),
            'completeness':'Only three primary participant TextGrids are published. Listener is not annotated. Early windows selected to avoid later Listener/retelling stage; extra-voice absence not independently listened/verified.',
            'listen_validation':'pending independent listening; no listening or model-derived reference claimed',
            'source_audio_header':header_source,'source_audio_range':audio_source,'source_annotation_files':sources,
            'source_full_audio_sha256':None,
            'changes':'Contiguous original shared-microphone PCM range only; stereo48k downmixed/resampled to mono16k PCM16. Reference cropped and shifted by same source offset.'}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache',required=True,type=Path)
    parser.add_argument('--python-deps',type=Path,help='existing miniaudio dependency directory; installs nothing')
    parser.add_argument('--sessions',nargs='+',choices=sorted(SESSION_STARTS),default=list(SESSION_STARTS))
    args=parser.parse_args(argv)
    root=args.cache.resolve();root.mkdir(parents=True,exist_ok=True)
    if args.python_deps:
        sys.path.insert(0,str(args.python_deps.resolve()))
    import miniaudio
    manifest=root/'manifest.json'
    document=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else {'fixtures':[]}
    for session in args.sessions:
        row=prepare(root,session,miniaudio)
        document['fixtures']=[item for item in document['fixtures'] if item['id']!=row['id']]+[row]
        document['privacy']='Public research corpus, no private user audio; cached audio must not be redistributed without clear rights.'
        manifest.write_text(json.dumps(document,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(row,ensure_ascii=False,indent=2),flush=True)
    return 0


if __name__=='__main__':
    raise SystemExit(main())
