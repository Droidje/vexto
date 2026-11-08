#!/usr/bin/env python3
"""Score one independently annotated fixture against Android job artifacts.

No inference, downloads, device access or transcript text in the output. DER uses
score_diarization's exact global mapping, zero collar, overlap scored and explicit
[0, duration) UEM. WER uses tools/eval/score.py normalization and edit distance;
turns are concatenated by start_ms, without speaker attribution. Enable WER only
when reference-json contains an independent published transcript. Missing runtime
measurements remain null. Source basenames and SHA256s identify inputs portably.

Example:
  python tools/benchmarks/score_synthetic_fixture.py --reference-rttm ref.rttm \
    --reference-json ref.json --transcript transcript.json \
    --completion completion.json --duration-seconds 60 --output metrics.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.benchmarks.score_diarization import parse_rttm, score
from tools.eval.score import normalize, wer


def number(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f'{label} must be a finite nonnegative number')
    if not math.isfinite(value) or value < 0:
        raise ValueError(f'{label} must be a finite nonnegative number')
    return value


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'Duplicate JSON field: {key}')
        result[key] = value
    return result


def decode_json(data, label):
    def invalid_constant(_):
        raise ValueError(f'{label} contains a nonfinite JSON number')
    result = json.loads(data.decode('utf-8-sig'), object_pairs_hook=no_duplicates,
                        parse_constant=invalid_constant)
    if not isinstance(result, dict):
        raise ValueError(f'{label} must be a JSON object')
    return result


def transcript_rows(data, label, strict=False):
    if strict and set(data) != {'transcript'}:
        raise ValueError(f'{label} must have exactly the top-level transcript field')
    rows = data.get('transcript')
    if not isinstance(rows, list):
        raise ValueError(f'{label}.transcript must be an array')
    expected = {'speaker_id', 'start_ms', 'end_ms', 'text'}
    for index, row in enumerate(rows):
        tag = f'{label}.transcript[{index}]'
        if not isinstance(row, dict) or set(row) != expected:
            raise ValueError(f'{tag} must contain exactly speaker_id/start_ms/end_ms/text')
        for key in ('start_ms', 'end_ms'):
            if not isinstance(row[key], int) or isinstance(row[key], bool):
                raise ValueError(f'{tag}.{key} must be an integer')
            number(row[key], f'{tag}.{key}')
        if row['end_ms'] <= row['start_ms']:
            raise ValueError(f'{tag} must have end_ms > start_ms')
        if not isinstance(row['speaker_id'], str) or not row['speaker_id'].strip():
            raise ValueError(f'{tag}.speaker_id must be a nonempty string')
        if not isinstance(row['text'], str):
            raise ValueError(f'{tag}.text must be a string')
    return rows


def segments(rows):
    return [(r['start_ms'] / 1000, r['end_ms'] / 1000, r['speaker_id']) for r in rows]


def optional_number(data, key):
    return None if data.get(key) is None else number(data[key], key)


def optional_bool(data, key):
    value = data.get(key)
    if value is not None and not isinstance(value, bool):
        raise ValueError(f'{key} must be boolean or null')
    return value


def numeric_map(data, key):
    value = data.get(key)
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ValueError(f'{key} must be an object or null')
    return {name: number(item, f'{key}.{name}') if item is not None else None
            for name, item in value.items()}


def raw_diarization(completion, reference, duration):
    stages = completion.get('stages')
    if stages is None:
        return {'status': 'unavailable_missing_stages', 'der': None}
    if not isinstance(stages, list) or any(not isinstance(s, dict) for s in stages):
        raise ValueError('completion.stages must be an array of objects')
    matches = [s for s in stages if s.get('stage') == 'DIARIZE']
    if len(matches) > 1:
        raise ValueError('Multiple DIARIZE stages are ambiguous')
    if not matches:
        return {'status': 'unavailable_missing_diarize_stage', 'der': None}
    stage = matches[0]
    completed = optional_bool(stage, 'completed')
    if completed is False or stage.get('error') is not None:
        return {'status': 'unavailable_diarize_not_successful', 'der': None,
                'stage_completed': completed}
    if 'turns' not in stage:
        return {'status': 'unavailable_missing_turns', 'der': None}
    if not isinstance(stage['turns'], list):
        raise ValueError('DIARIZE.turns must be an array')
    turns = []
    for index, turn in enumerate(stage['turns']):
        label = f'DIARIZE.turns[{index}]'
        if not isinstance(turn, dict):
            raise ValueError(f'{label} must be an object')
        start = number(turn.get('startMs'), f'{label}.startMs')
        end = number(turn.get('endMs'), f'{label}.endMs')
        speaker = turn.get('speaker')
        if isinstance(speaker, bool) or not isinstance(speaker, (str, int)):
            raise ValueError(f'{label}.speaker must be an integer or nonempty string')
        if not str(speaker).strip():
            raise ValueError(f'{label}.speaker must not be empty')
        if end <= start:
            raise ValueError(f'{label} must have endMs > startMs')
        turns.append((start / 1000, end / 1000, str(speaker)))
    return dict(score(reference, turns, duration), stage_completed=completed,
                hypothesis_segments=len(turns))


def language_info(completion):
    value = completion.get('language')
    if value is None:
        return None
    if isinstance(value, str):
        return {'code': value}
    if not isinstance(value, dict):
        raise ValueError('completion.language must be a string, object or null')
    # Preserve only public language diagnostics, never paths or source text.
    result = {}
    for key in ('code', 'route'):
        if value.get(key) is not None:
            if not isinstance(value[key], str):
                raise ValueError(f'language.{key} must be a string')
            result[key] = value[key]
    for key in ('samples', 'confidence'):
        if value.get(key) is not None:
            result[key] = number(value[key], f'language.{key}')
    if 'detected' in value:
        result['detected'] = optional_bool(value, 'detected')
    return result


def evaluate(args):
    paths = {'reference_rttm': args.reference_rttm, 'reference_json': args.reference_json,
             'transcript': args.transcript, 'completion': args.completion}
    if args.output.resolve() in {p.resolve() for p in paths.values()}:
        raise ValueError('Output must not overwrite any input')
    blobs = {key: path.read_bytes() for key, path in paths.items()}
    reference, rttm_info = parse_rttm(blobs['reference_rttm'].decode('utf-8-sig'))
    ref_json = decode_json(blobs['reference_json'], 'reference_json')
    hyp = transcript_rows(decode_json(blobs['transcript'], 'transcript'), 'transcript', True)
    completion = decode_json(blobs['completion'], 'completion')
    duration = number(args.duration_seconds, 'duration_seconds')
    if duration <= 0:
        raise ValueError('duration_seconds must be positive')
    transcript_sha = hashlib.sha256(blobs['transcript']).hexdigest()
    if completion.get('transcriptSha256') is not None and completion['transcriptSha256'] != transcript_sha:
        raise ValueError('completion.transcriptSha256 does not match transcript input')
    wer_result = {'status': 'unavailable_no_independent_lexical_reference', 'value': None,
                  'percent': None, 'errors': None, 'reference_words': None,
                  'hypothesis_words': sum(len(normalize(r['text'])) for r in hyp)}
    if args.wer_reference_available:
        ref_rows = transcript_rows(ref_json, 'reference_json')
        errors, words = wer(ref_rows, hyp)
        value = errors / words if words else None
        wer_result.update(status='scored' if words else 'undefined_zero_reference_words',
                          value=value, percent=100 * value if value is not None else None,
                          errors=errors, reference_words=words,
                          protocol='start-time order; lowercase; ё->е; punctuation/underscore removed; no speaker attribution')
    processing_ms = optional_number(completion, 'processingMs')
    exported = dict(score(reference, segments(hyp), duration), hypothesis_segments=len(hyp))
    return {
        'schema_version': 1,
        'duration_seconds': duration,
        'inputs': {key: {'name': path.name, 'sha256': hashlib.sha256(blobs[key]).hexdigest()}
                   for key, path in paths.items()},
        'reference': rttm_info,
        'exportedDER': exported,
        'rawNemotronDER': raw_diarization(completion, reference, duration),
        'wer': wer_result,
        'runtime': {
            'processing_ms': processing_ms,
            'rtf': processing_ms / (duration * 1000) if processing_ms is not None else None,
            'reported_audio_ms': optional_number(completion, 'audioMs'),
            'peak_rss_bytes': optional_number(completion, 'peakRssBytes'),
            'peak_pss_bytes': optional_number(completion, 'peakPssBytes'),
            'memory_note': 'Reported Android job RSS/PSS if present; no host memory measurement or inferred zero.',
            'stage_ms': numeric_map(completion, 'stageMs')},
        'complete': optional_bool(completion, 'complete'),
        'full_profile_completed': optional_bool(completion, 'fullProfileCompleted'),
        'selection_resolved': optional_bool(completion, 'selectionResolved'),
        'coverage': numeric_map(completion, 'coverage'),
        'language': language_info(completion),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for key in ('reference-rttm', 'reference-json', 'transcript', 'completion', 'output'):
        parser.add_argument('--' + key, required=True, type=Path)
    parser.add_argument('--duration-seconds', required=True, type=float)
    parser.add_argument('--wer-reference-available', action='store_true',
                        help='Score WER only with an independent published transcript in reference-json')
    args = parser.parse_args(argv)
    try:
        result = evaluate(args)
        output = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding='utf-8')
        print('Fixture metrics written; no source text included.')
        return 0
    except (OSError, UnicodeError, ValueError, OverflowError) as error:
        print(f'score_synthetic_fixture: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
