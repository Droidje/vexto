"""Single-recording DER: collar=0, overlap scored, UEM=[0, duration).

Speaker counts are active SET sizes (duplicate/overlapping spans of one label
count once). Global optimal one-to-one label mapping is exact for <=8 speakers
per side. DER is undefined (JSON null) when reference speaker-time is zero.
This explicit protocol must not be compared with other corpus/collar setups.
No model participates in reference construction or scoring.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
from itertools import permutations
import json
import math
from pathlib import Path
import re
import sys


def finite_nonnegative(value, name):
    if isinstance(value, bool):
        raise ValueError(f'{name} must be finite and nonnegative')
    try:
        value = float(value)
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError(f'{name} must be finite and nonnegative') from error
    if not math.isfinite(value) or value < 0:
        raise ValueError(f'{name} must be finite and nonnegative')
    return value


def interval(start, end, speaker):
    start = finite_nonnegative(start, 'start')
    end = finite_nonnegative(end, 'end')
    if end <= start:
        raise ValueError('interval must have end > start')
    if not isinstance(speaker, str) or not speaker.strip():
        raise ValueError('speaker must be a nonempty string')
    return start, end, speaker


def parse_rttm(text):
    segments, recordings, channels = [], set(), set()
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        fields = line.split()
        if len(fields) != 10 or fields[0] != 'SPEAKER':
            raise ValueError(f'RTTM line {number}: expected ten-field SPEAKER row')
        start = finite_nonnegative(fields[3], f'RTTM line {number} start')
        duration = finite_nonnegative(fields[4], f'RTTM line {number} duration')
        segments.append(interval(start, start + duration, fields[7]))
        recordings.add(fields[1])
        channels.add(fields[2])
    if len(recordings) > 1 or len(channels) > 1:
        raise ValueError('Only one recording and channel per RTTM input are supported')
    return segments, {'format':'rttm', 'recording':next(iter(recordings), None),
                      'channel':next(iter(channels), None)}


NUMBER = r'(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?'
LEGACY = re.compile(rf'^\s*({NUMBER})\s+--\s+({NUMBER})\s+(\S+)\s*$')


def parse_hypothesis(text):
    stripped = text.lstrip('\ufeff \t\r\n')
    first_row = next((line.lstrip() for line in stripped.splitlines()
                      if line.strip() and not line.lstrip().startswith('#')), '')
    if first_row.startswith('SPEAKER') or not first_row:
        return parse_rttm(stripped)
    if stripped.startswith('['):
        rows = json.loads(stripped)
        if not isinstance(rows, list):
            raise ValueError('Legacy JSON must be a list of segment objects')
        segments = []
        for row in rows:
            if not isinstance(row, dict):
                raise ValueError('Legacy JSON segment must be an object')
            if 'start' in row and 'end' in row:
                start, end = row['start'], row['end']
            elif 'start_s' in row and 'end_s' in row:
                start, end = row['start_s'], row['end_s']
            else:
                raise ValueError('Legacy JSON needs start/end or start_s/end_s')
            segments.append(interval(start, end, row.get('speaker')))
        return segments, {'format':'legacy-json'}
    segments, ignored = [], 0
    for line in text.splitlines():
        if not line.strip():
            continue
        match = LEGACY.fullmatch(line)
        if match:
            segments.append(interval(*match.groups()))
        else:
            # Runtime diagnostics may accompany sherpa stdout. Do not silently
            # interpret malformed interval-looking rows as diagnostics.
            if '--' in line or line.lstrip().startswith('SPEAKER'):
                raise ValueError(f'Malformed diarization row: {line}')
            ignored += 1
    if not segments:
        raise ValueError('Nonempty hypothesis has no recognized diarization rows')
    return segments, {'format':'legacy-text', 'ignored_diagnostic_lines':ignored}


def _regions(reference, hypothesis, duration):
    events = defaultdict(list)
    events[0.0]; events[duration]
    labels = [set(), set()]
    for side, segments in enumerate((reference, hypothesis)):
        for segment in segments:
            if not isinstance(segment, (tuple, list)) or len(segment) != 3:
                raise ValueError('Segments must be (start, end, speaker) triples')
            start, end, speaker = interval(*segment)
            start, end = max(0.0, start), min(duration, end)
            if end <= start:
                continue
            events[start].append((side, speaker, 1))
            events[end].append((side, speaker, -1))
            labels[side].add(speaker)
    if max(map(len, labels)) > 8:
        raise ValueError('Exact scorer supports at most eight active-in-UEM labels per side')
    active = [Counter(), Counter()]
    points = sorted(events)
    regions = []
    for index, start in enumerate(points[:-1]):
        for side, label, delta in events[start]:
            active[side][label] += delta
            if not active[side][label]:
                del active[side][label]
        end = points[index + 1]
        regions.append((end - start, frozenset(active[0]), frozenset(active[1])))
    return regions, sorted(labels[0]), sorted(labels[1])


def score(reference, hypothesis, duration):
    """Score second-based segment triples; intervals outside UEM are clipped."""
    duration = finite_nonnegative(duration, 'UEM duration')
    if duration <= 0:
        raise ValueError('UEM duration must be positive')
    regions, ref_labels, hyp_labels = _regions(reference, hypothesis, duration)
    pairs = defaultdict(float)
    for length, refs, hyps in regions:
        for hyp in hyps:
            for ref in refs:
                pairs[hyp, ref] += length
    size = max(len(ref_labels), len(hyp_labels))
    matrix = [[pairs[h, r] for r in ref_labels] + [0.0] * (size - len(ref_labels))
              for h in hyp_labels]
    matrix += [[0.0] * size for _ in range(size - len(hyp_labels))]
    best, assignment = -1.0, ()
    for candidate in permutations(range(size)):
        value = math.fsum(matrix[row][column] for row, column in enumerate(candidate))
        if value > best:
            best, assignment = value, candidate
    mapping = {hyp:ref_labels[assignment[index]] if assignment[index] < len(ref_labels) else None
               for index, hyp in enumerate(hyp_labels)}
    misses, false_alarms, confusions, denominators = [], [], [], []
    for length, refs, hyps in regions:
        correct = sum(mapping[h] in refs for h in hyps)
        misses.append(length * max(len(refs) - len(hyps), 0))
        false_alarms.append(length * max(len(hyps) - len(refs), 0))
        confusions.append(length * (min(len(refs), len(hyps)) - correct))
        denominators.append(length * len(refs))
    miss, false_alarm, confusion, denominator = map(math.fsum,
        (misses, false_alarms, confusions, denominators))
    error = math.fsum((miss, false_alarm, confusion))
    der = error / denominator if denominator else None
    return {'status':'scored' if denominator else 'undefined_zero_reference',
            'der':der, 'der_percent':100 * der if der is not None else None,
            'miss_speaker_seconds':miss, 'false_alarm_speaker_seconds':false_alarm,
            'confusion_speaker_seconds':confusion, 'error_speaker_seconds':error,
            'reference_speaker_seconds':denominator,
            'mapping_hypothesis_to_reference':mapping,
            'unmapped_reference_labels':sorted(set(ref_labels) - set(mapping.values())),
            'reference_labels':ref_labels, 'hypothesis_labels':hyp_labels,
            'collar_seconds':0, 'overlap_scored':True, 'uem':{'start':0, 'end':duration},
            'protocol':'single-recording active-set DER, global one-to-one mapping, collar 0, overlap scored'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', required=True, type=Path, help='independent reference RTTM')
    parser.add_argument('--hypothesis', required=True, type=Path, help='RTTM or legacy start -- end speaker / JSON')
    parser.add_argument('--duration', required=True, type=float, help='explicit UEM [0, duration), in seconds')
    parser.add_argument('--output', type=Path, help='JSON output; stdout if omitted')
    args = parser.parse_args(argv)
    if args.output and args.output.resolve() in (args.reference.resolve(), args.hypothesis.resolve()):
        parser.error('Output must not overwrite either input')
    try:
        reference_bytes, hypothesis_bytes = args.reference.read_bytes(), args.hypothesis.read_bytes()
        reference, ref_info = parse_rttm(reference_bytes.decode('utf-8-sig'))
        hypothesis, hyp_info = parse_hypothesis(hypothesis_bytes.decode('utf-8-sig'))
        result = score(reference, hypothesis, args.duration)
        result['inputs'] = {'reference':dict(ref_info, sha256=hashlib.sha256(reference_bytes).hexdigest()),
                            'hypothesis':dict(hyp_info, sha256=hashlib.sha256(hypothesis_bytes).hexdigest())}
        text = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(text, encoding='utf-8')
        else:
            print(text, end='')
        return 0
    except (OSError, UnicodeError, ValueError, OverflowError) as error:
        print(f'score_diarization: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
