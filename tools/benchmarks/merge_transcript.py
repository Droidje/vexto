"""Deterministic benchmark export; no inference or transcript rewriting.

Run: python tools/benchmarks/merge_transcript.py --asr-json asr.json
     --diarization-json speakers.json --output transcript.json
The contract JSON has a sibling *.provenance.json preserving exact source text.
Word boundaries use token emission times, not forced alignment. A word ends
40 ms after its last token timestamp, making inter-word pauses observable.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys

TOKEN_TAIL_SECONDS = 0.04
MAX_GROUP_PAUSE_SECONDS = 0.7


def finite_time(value, location):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f'{location} must be a finite nonnegative number')
    try:
        result = float(value)
    except OverflowError as error:
        raise ValueError(f'{location} is outside the supported numeric range') from error
    if not math.isfinite(result) or result < 0 or not math.isfinite(result * 1000):
        raise ValueError(f'{location} must be a finite nonnegative number')
    return result


def validate_asr(asr):
    if not isinstance(asr, dict) or not isinstance(asr.get('text'), str):
        raise ValueError('ASR must be an object with string text')
    text, tokens, times = asr['text'], asr.get('tokens'), asr.get('timestamps')
    if not isinstance(tokens, list) or any(not isinstance(t, str) for t in tokens):
        raise ValueError('ASR tokens must be a list of strings')
    if ''.join(tokens).strip() != text.strip():
        raise ValueError('Joined ASR tokens do not exactly match stripped text')
    if not isinstance(times, list) or len(times) != len(tokens):
        raise ValueError('ASR timestamps and tokens must have equal lengths')
    times = [finite_time(value, f'timestamps[{i}]') for i, value in enumerate(times)]
    if any(after < before for before, after in zip(times, times[1:])):
        raise ValueError('ASR timestamps must be monotonically nondecreasing')
    return text, tokens, times


def diarization_intervals(diarization):
    if not isinstance(diarization, list):
        raise ValueError('Diarization must be a list of start/end/speaker objects')
    intervals = []
    for index, segment in enumerate(diarization):
        if not isinstance(segment, dict):
            raise ValueError(f'diarization[{index}] must be an object')
        speaker = segment.get('speaker')
        if not isinstance(speaker, str) or not speaker.strip():
            raise ValueError(f'diarization[{index}].speaker must be a nonempty string')
        start = finite_time(segment.get('start'), f'diarization[{index}].start')
        end = finite_time(segment.get('end'), f'diarization[{index}].end')
        if end <= start:
            raise ValueError(f'diarization[{index}] must have end > start')
        intervals.append((start, end, speaker))
    intervals.sort()
    mapping = {}
    merged = {}
    for start, end, speaker in intervals:
        if speaker not in mapping:
            mapping[speaker] = f'speaker_{len(mapping) + 1}'
        speaker_id = mapping[speaker]
        spans = merged.setdefault(speaker_id, [])
        if spans and start <= spans[-1][1]:
            spans[-1] = (spans[-1][0], max(spans[-1][1], end))
        else:
            spans.append((start, end))
    return mapping, merged


def word_spans(text, tokens, times):
    joined = ''.join(tokens)
    # Alignment is exact after stripping only external whitespace. Internal
    # whitespace and punctuation must never be normalized to force a match.
    token_origin = len(joined) - len(joined.lstrip())
    text_origin = len(text) - len(text.lstrip())
    token_ranges = []
    position = 0
    for token in tokens:
        token_ranges.append((position, position + len(token)))
        position += len(token)
    words = []
    token_index = 0
    for match in re.finditer(r'\S+', text):
        first_char = match.start() - text_origin + token_origin
        end_char = match.end() - text_origin + token_origin
        while token_index < len(tokens) and token_ranges[token_index][1] <= first_char:
            token_index += 1
        first_token = token_index
        last_token = first_token
        while last_token + 1 < len(tokens) and token_ranges[last_token + 1][0] < end_char:
            last_token += 1
        start = times[first_token]
        end = times[last_token] + TOKEN_TAIL_SECONDS
        if not math.isfinite(end) or end <= start:
            raise ValueError('Token times cannot form a positive finite word span')
        words.append({'text': match.group(), 'char_start': match.start(),
                      'char_end': match.end(), 'first_token': first_token,
                      'last_token': last_token, 'start_s': start, 'end_s': end})
    return words


def assign_speaker(word, intervals):
    overlaps = []
    for speaker, spans in intervals.items():
        overlap = sum(max(0.0, min(word['end_s'], end) - max(word['start_s'], start))
                      for start, end in spans)
        if overlap > 0:
            overlaps.append((speaker, overlap))
    # intervals retains acoustic-first order, also used for deterministic ties.
    best_overlap = max((value for _, value in overlaps), default=0.0)
    tied = [speaker for speaker, value in overlaps
            if math.isclose(value, best_overlap, rel_tol=0, abs_tol=1e-9)]
    return {'speaker_id': tied[0] if tied else 'unknown',
            'ambiguous': len(tied) > 1,
            'overlap_seconds': dict(overlaps)}


def merge_transcript(asr, diarization):
    text, tokens, times = validate_asr(asr)
    mapping, intervals = diarization_intervals(diarization)
    words = word_spans(text, tokens, times)
    groups = []
    for word in words:
        word.update(assign_speaker(word, intervals))
        if (groups and groups[-1]['speaker_id'] == word['speaker_id']
                and word['start_s'] - groups[-1]['last_word_end_s'] <= MAX_GROUP_PAUSE_SECONDS + 1e-9):
            group = groups[-1]
            group['end_s'] = max(group['end_s'], word['end_s'])
            group['last_word_end_s'] = word['end_s']
            group['char_end'] = word['char_end']
        else:
            groups.append({'speaker_id': word['speaker_id'], 'start_s': word['start_s'],
                           'end_s': word['end_s'], 'last_word_end_s': word['end_s'],
                           'char_start': word['char_start'], 'char_end': word['char_end']})
    transcript = [{'speaker_id': group['speaker_id'],
                   'start_ms': math.floor(group['start_s'] * 1000),
                   'end_ms': math.ceil(group['end_s'] * 1000),
                   'text': text[group['char_start']:group['char_end']]}
                  for group in groups]
    if [w for row in transcript for w in row['text'].split()] != text.split():
        raise ValueError('Export would alter the original lexical sequence')
    provenance = {'raw_asr_text': text, 'speaker_mapping': mapping,
                  'timing_method': 'first/last contributing token timestamps; fixed 40 ms word tail',
                  'token_tail_seconds': TOKEN_TAIL_SECONDS,
                  'group_pause_seconds': MAX_GROUP_PAUSE_SECONDS,
                  'time_rounding': 'start floor/end ceil to milliseconds',
                  'speaker_assignment': 'largest union overlap; acoustic-first ID breaks ties',
                  'limitations': ['Token emission spans are estimates, not forced alignment.',
                                  'Overlap assignment is not proof of speaker identity.',
                                  'Source whitespace between separate rows remains in raw_asr_text.'],
                  'words': words}
    return {'transcript': transcript}, provenance


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--asr-json', required=True, type=Path)
    parser.add_argument('--diarization-json', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(argv)
    sidecar = args.output.with_name(args.output.stem + '.provenance.json')
    inputs = {args.asr_json.resolve(), args.diarization_json.resolve()}
    if args.output.resolve() in inputs or sidecar.resolve() in inputs:
        parser.error('Output paths must not overwrite either input')
    try:
        asr_bytes = args.asr_json.read_bytes()
        diarization_bytes = args.diarization_json.read_bytes()
        result, provenance = merge_transcript(json.loads(asr_bytes.decode('utf-8-sig')),
                                              json.loads(diarization_bytes.decode('utf-8-sig')))
        provenance['input_sha256'] = {'asr': hashlib.sha256(asr_bytes).hexdigest(),
                                      'diarization': hashlib.sha256(diarization_bytes).hexdigest()}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
        sidecar.write_text(json.dumps(provenance, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    except (ValueError, OSError) as error:
        print(f'Cannot merge transcript: {error}', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
