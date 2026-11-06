"""Score debug dev-pipeline raw RTTM against the same preselected Pears references.

No ASR intervals, inference, ADB, or network. Inputs may contain private paths;
output uses an explicit sanitized whitelist. Incomplete/failed probes never yield
a successful pooled DER. The reference is read here on the host, never by APK.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import statistics
import sys

_SPEC = importlib.util.spec_from_file_location('_dev_der_scorer', Path(__file__).with_name('score_diarization.py'))
_SCORER = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SCORER)
DEFAULT_BASELINE = Path(__file__).resolve().parents[2] / 'docs/research/results/russian-diarization-2026-11-07.json'
BASELINE_MODEL = 'nemotron-f32'
FIELDS = ('miss_speaker_seconds', 'false_alarm_speaker_seconds', 'confusion_speaker_seconds',
          'error_speaker_seconds', 'reference_speaker_seconds')
SAFE_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,100}\Z')
SHA = re.compile(r'[0-9a-f]{64}\Z')


class Rejected(ValueError):
    """Only fixed reason codes go into output; never expose raw IO/error text."""


def read_json(path, reason):
    try:
        data = path.read_bytes()
        value = json.loads(data.decode('utf-8-sig'), parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(value, dict):
            raise ValueError()
        return value, hashlib.sha256(data).hexdigest()
    except (OSError, UnicodeError, ValueError):
        raise Rejected(reason) from None


def number(value, reason, positive=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise Rejected(reason)
    try:
        finite = math.isfinite(value)
    except OverflowError:
        raise Rejected(reason) from None
    if not finite or value < 0 or (positive and value == 0):
        raise Rejected(reason)
    return value


def hash_value(value, reason):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise Rejected(reason)
    return value


def local_path(manifest, value):
    if not isinstance(value, str) or not value:
        raise Rejected('invalid_manifest_path')
    path = Path(value)
    return path if path.is_absolute() else manifest.parent / path


def read_checked(path, expected, reason):
    hash_value(expected, reason)
    try:
        data = path.read_bytes()
    except OSError:
        raise Rejected(reason) from None
    if hashlib.sha256(data).hexdigest() != expected:
        raise Rejected(reason)
    return data


def fixtures_by_id(document):
    rows = document.get('fixtures')
    if not isinstance(rows, list) or not rows:
        raise Rejected('invalid_fixture_set')
    result = {}
    for row in rows:
        key = row.get('id') if isinstance(row, dict) else None
        if not isinstance(key, str) or not SAFE_ID.fullmatch(key) or key in result:
            raise Rejected('invalid_fixture_id')
        number(row.get('duration_seconds'), 'invalid_duration', positive=True)
        hash_value(row.get('audio_sha256'), 'invalid_audio_hash')
        hash_value(row.get('rttm_sha256'), 'invalid_reference_hash')
        result[key] = row
    return result


def checked_score(value, duration):
    if not isinstance(value, dict) or value.get('status') != 'scored':
        raise Rejected('baseline_score_missing')
    if value.get('collar_seconds') != 0 or value.get('overlap_scored') is not True:
        raise Rejected('baseline_protocol_mismatch')
    if value.get('uem') != {'start':0, 'end':duration}:
        raise Rejected('baseline_uem_mismatch')
    result = {field:number(value.get(field), 'baseline_invalid_score') for field in FIELDS}
    if not result['reference_speaker_seconds']:
        raise Rejected('baseline_zero_reference')
    expected_error = math.fsum(result[field] for field in FIELDS[:3])
    if not math.isclose(expected_error, result['error_speaker_seconds'], abs_tol=1e-7):
        raise Rejected('baseline_invalid_score')
    result['der'] = result['error_speaker_seconds'] / result['reference_speaker_seconds']
    if not math.isclose(result['der'], number(value.get('der'), 'baseline_invalid_score'), abs_tol=1e-9):
        raise Rejected('baseline_invalid_score')
    result['der_percent'] = result['der'] * 100
    return result


def baseline_rows(baseline, fixtures):
    original = fixtures_by_id(baseline)
    if set(original) != set(fixtures):
        raise Rejected('baseline_fixture_set_mismatch')
    for key, row in fixtures.items():
        for field in ('audio_sha256', 'rttm_sha256', 'duration_seconds'):
            if row[field] != original[key].get(field):
                raise Rejected('baseline_fixture_identity_mismatch')
    matches = {}
    runs = baseline.get('android_runs')
    if not isinstance(runs, list) or any(not isinstance(run, dict) for run in runs):
        raise Rejected('baseline_runs_invalid')
    for run in runs:
        if run.get('model_id') != BASELINE_MODEL:
            continue
        key = run.get('fixture_id')
        if key not in fixtures or key in matches or run.get('accepted') is not True or run.get('status') != 'accepted':
            raise Rejected('baseline_run_invalid')
        matches[key] = checked_score(run.get('score'), fixtures[key]['duration_seconds'])
    if set(matches) != set(fixtures):
        raise Rejected('baseline_runs_incomplete')
    return matches


def measurement(stats, row):
    if stats.get('schema_version') != 1 or stats.get('status') != 'completed':
        raise Rejected('probe_not_completed')
    if stats.get('fixture_id', row['id']) != row['id']:
        raise Rejected('probe_fixture_mismatch')
    if stats.get('audio_sha256') != row['audio_sha256']:
        raise Rejected('probe_audio_hash_mismatch')
    if 'forced_speaker_count' not in stats or stats['forced_speaker_count'] is not None:
        raise Rejected('forced_speaker_count_not_allowed')
    if stats.get('asr_executed') is not False:
        raise Rejected('raw_diarization_only_required')
    audio_ms = number(stats.get('audio_ms'), 'invalid_probe_duration', positive=True)
    if not math.isclose(audio_ms / 1000, row['duration_seconds'], abs_tol=.001):
        raise Rejected('probe_duration_mismatch')
    model_hash = hash_value(stats.get('model_manifest_sha256'), 'invalid_model_manifest_hash')
    threads = number(stats.get('threads'), 'invalid_threads', positive=True)
    if not isinstance(threads, int):
        raise Rejected('invalid_threads')
    wall = number(stats.get('wall_ms'), 'invalid_wall_time', positive=True) / 1000
    stages = stats.get('stage_ms')
    if not isinstance(stages, dict):
        raise Rejected('invalid_stage_times')
    stage_seconds = {key:number(stages.get(key), 'invalid_stage_times') / 1000
                     for key in ('prepare', 'decode', 'vad', 'diarization')}
    peak = number(stats.get('peak_rss_bytes'), 'invalid_peak_rss', positive=True)
    reset = stats.get('peak_reset_succeeded')
    if not isinstance(reset, bool):
        raise Rejected('invalid_peak_scope')
    return {'wall_seconds':wall, 'process_rtf':wall / row['duration_seconds'],
            'stage_seconds':stage_seconds, 'peak_rss_bytes':peak,
            'peak_reset_succeeded':reset,
            'peak_scope':'after_model_installation' if reset else 'process_lifetime',
            'memory_metric':'Android app process VmHWM; not PSS or a hard memory bound',
            'threads':threads, 'model_manifest_sha256':model_hash,
            'forced_speaker_count':None, 'asr_executed':False}


def pooled(scores):
    totals = {field:math.fsum(row[field] for row in scores) for field in FIELDS}
    totals['der'] = totals['error_speaker_seconds'] / totals['reference_speaker_seconds']
    totals['der_percent'] = totals['der'] * 100
    return totals


def evaluate(manifest_path, hypotheses_dir, baseline_path=DEFAULT_BASELINE):
    manifest, manifest_hash = read_json(manifest_path, 'manifest_unreadable')
    baseline, baseline_hash = read_json(baseline_path, 'baseline_unreadable')
    fixtures = fixtures_by_id(manifest)
    comparison = baseline_rows(baseline, fixtures)
    rows = []
    for key, fixture in fixtures.items():
        item = {'fixture_id':key, 'audio_sha256':fixture['audio_sha256'],
                'reference_rttm_sha256':fixture['rttm_sha256'], 'duration_seconds':fixture['duration_seconds']}
        try:
            read_checked(local_path(manifest_path, fixture.get('wav')), fixture['audio_sha256'], 'local_audio_hash_mismatch')
            reference = read_checked(local_path(manifest_path, fixture.get('rttm')), fixture['rttm_sha256'], 'local_reference_hash_mismatch')
            stats, stats_hash = read_json(hypotheses_dir / f'{key}.stats.json', 'stats_unreadable')
            measurements = measurement(stats, fixture)
            output_hash = hash_value(stats.get('hypothesis_rttm_sha256'), 'missing_hypothesis_hash')
            hypothesis = read_checked(hypotheses_dir / f'{key}.rttm', output_hash, 'hypothesis_hash_mismatch')
            try:
                ref, _ = _SCORER.parse_rttm(reference.decode('utf-8-sig'))
                hyp, _ = _SCORER.parse_rttm(hypothesis.decode('utf-8-sig'))
                if any(not SAFE_ID.fullmatch(label) for _, _, label in ref + hyp):
                    raise ValueError()
                scored = _SCORER.score(ref, hyp, fixture['duration_seconds'])
            except (UnicodeError, ValueError, OverflowError):
                raise Rejected('invalid_raw_rttm') from None
            if scored['status'] != 'scored':
                raise Rejected('zero_reference_activity')
            if not math.isclose(scored['reference_speaker_seconds'], comparison[key]['reference_speaker_seconds'], abs_tol=1e-7):
                raise Rejected('baseline_reference_activity_mismatch')
            item.update(status='scored', score=scored, measurements=measurements,
                        hypothesis_rttm_sha256=output_hash, stats_sha256=stats_hash,
                        nemotron_baseline=comparison[key],
                        delta_der_percentage_points=scored['der_percent']-comparison[key]['der_percent'])
        except Rejected as error:
            item.update(status='rejected', reason_code=str(error))
        rows.append(item)
    complete = all(row['status'] == 'scored' for row in rows)
    signatures = {(row['measurements']['model_manifest_sha256'], row['measurements']['threads'])
                  for row in rows if row['status'] == 'scored'}
    if len(signatures) > 1:
        complete = False
    report = {'schema_version':1, 'status':'completed' if complete else 'incomplete',
              'pipeline':'dev production Silero VAD + ERes2Net-base + custom clustering, raw turns',
              'protocol':{'collar_seconds':0, 'overlap_scored':True, 'uem':'each fixture [0,duration)',
                          'mapping':'global one-to-one per recording; micro DER from pooled speaker-seconds',
                          'same_clips_and_reference_hashes_as_baseline':True,
                          'scope':'diagnostic Pears subset; not cross-corpus benchmarking',
                          'reference_caveat':'Only primary N/C/R manual Words tiers; Listener unannotated; completeness/listening not independently confirmed'},
              'manifest_sha256':manifest_hash, 'baseline_json_sha256':baseline_hash,
              'baseline_model_id':BASELINE_MODEL, 'fixtures':rows, 'aggregate':None, 'comparison':None}
    if len(signatures) > 1:
        report['reason_code'] = 'probe_configuration_mismatch'
    if complete:
        dev = pooled([row['score'] for row in rows])
        base = pooled(list(comparison.values()))
        dev['median_app_wall_seconds'] = statistics.median(row['measurements']['wall_seconds'] for row in rows)
        dev['max_app_process_peak_rss_bytes'] = max(row['measurements']['peak_rss_bytes'] for row in rows)
        report['aggregate'] = dev
        report['comparison'] = {'nemotron_micro':base,
            'delta_der_percentage_points':dev['der_percent']-base['der_percent'],
            'interpretation':'negative delta means lower DER on these identical clips only',
            'measurement_note':'Dev timings/RSS cover Android app process; baseline uses native CLI; no direct memory equivalence is assumed'}
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--hypotheses-dir', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--baseline', type=Path, default=DEFAULT_BASELINE)
    args = parser.parse_args(argv)
    try:
        manifest, _ = read_json(args.manifest, 'manifest_unreadable')
        protected = {args.manifest.resolve(), args.baseline.resolve()}
        for key, row in fixtures_by_id(manifest).items():
            protected.update((local_path(args.manifest,row.get('wav')).resolve(), local_path(args.manifest,row.get('rttm')).resolve(),
                              (args.hypotheses_dir/f'{key}.rttm').resolve(), (args.hypotheses_dir/f'{key}.stats.json').resolve()))
        if args.output.resolve() in protected:
            print('score_dev_diarization: output_aliases_input', file=sys.stderr)
            return 2
        report = evaluate(args.manifest,args.hypotheses_dir,args.baseline)
    except Rejected as error:
        report = {'schema_version':1,'status':'rejected','reason_code':str(error)}
    try:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    except (OSError, ValueError):
        print('score_dev_diarization: output_write_failed', file=sys.stderr)
        return 2
    print('score_dev_diarization: '+report['status'])
    return 0 if report['status']=='completed' else 2


if __name__=='__main__':
    raise SystemExit(main())
