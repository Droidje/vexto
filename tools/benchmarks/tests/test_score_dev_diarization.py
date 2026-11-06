"""Synthetic files/stats only: identity gates, pooled DER and failed probes."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'score_dev_diarization.py'
SPEC = importlib.util.spec_from_file_location('score_dev_diarization', MODULE)
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value), encoding='utf-8')


class ScoreDevDiarizationTests(unittest.TestCase):
    def prepare(self, root):
        fixtures, runs = [], []
        hypotheses = root/'hypotheses'
        hypotheses.mkdir()
        for key, duration in [('short', 1), ('long', 3)]:
            audio = ('synthetic audio identity '+key).encode()
            reference = f'SPEAKER {key} 1 0 {duration} <NA> <NA> A <NA> <NA>\n'.encode()
            hypothesis = b'' if key=='short' else f'SPEAKER {key} 1 0 {duration} <NA> <NA> X <NA> <NA>\n'.encode()
            (root/f'{key}.wav').write_bytes(audio)
            (root/f'{key}.ref.rttm').write_bytes(reference)
            (hypotheses/f'{key}.rttm').write_bytes(hypothesis)
            fixtures.append({'id':key,'wav':f'{key}.wav','rttm':f'{key}.ref.rttm',
                             'audio_sha256':sha(audio),'rttm_sha256':sha(reference),'duration_seconds':duration})
            ref_rows, _ = helper._SCORER.parse_rttm(reference.decode())
            perfect = helper._SCORER.score(ref_rows,ref_rows,duration)
            runs.append({'fixture_id':key,'model_id':'nemotron-f32','status':'accepted','accepted':True,'score':perfect})
            stats={'schema_version':1,'status':'completed','audio_sha256':sha(audio),
                   'hypothesis_rttm_sha256':sha(hypothesis),'audio_ms':duration*1000,
                   'forced_speaker_count':None,'asr_executed':False,'threads':2,
                   'model_manifest_sha256':'a'*64,'wall_ms':100,
                   'stage_ms':{'prepare':10,'decode':10,'vad':20,'diarization':60},
                   'peak_rss_bytes':1000000,'peak_reset_succeeded':False,
                   'model_manifest':{'path':'C:/DO_NOT_COPY/private-model','token':'DO_NOT_COPY'},
                   'audio_file':'C:/DO_NOT_COPY/private-input.wav'}
            write_json(hypotheses/f'{key}.stats.json',stats)
        manifest, baseline = root/'manifest.json', root/'baseline.json'
        write_json(manifest,{'fixtures':fixtures})
        write_json(baseline,{'fixtures':fixtures,'android_runs':runs})
        return manifest,hypotheses,baseline

    def test_success_uses_pooled_speaker_seconds_and_sanitized_whitelist(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest,hypotheses,baseline=self.prepare(Path(directory))
            report=helper.evaluate(manifest,hypotheses,baseline)
            self.assertEqual(report['status'],'completed')
            self.assertEqual(report['aggregate']['reference_speaker_seconds'],4)
            self.assertEqual(report['aggregate']['miss_speaker_seconds'],1)
            self.assertEqual(report['aggregate']['der'],.25)  # macro average would be .5
            self.assertEqual(report['comparison']['delta_der_percentage_points'],25)
            encoded=json.dumps(report)
            self.assertNotIn('DO_NOT_COPY',encoded)
            self.assertNotIn(directory,encoded)
            self.assertNotIn('audio_file',encoded)
            self.assertTrue(report['protocol']['same_clips_and_reference_hashes_as_baseline'])
            self.assertEqual(report['fixtures'][0]['measurements']['peak_scope'],'process_lifetime')

    def test_hash_and_fixture_mismatches_fail_closed(self):
        for fault,expected in [('audio','local_audio_hash_mismatch'),
                               ('hypothesis','hypothesis_hash_mismatch'),
                               ('stats_audio','probe_audio_hash_mismatch'),
                               ('stats_fixture','probe_fixture_mismatch'),
                               ('missing_binding','missing_hypothesis_hash')]:
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                root=Path(directory)
                manifest,hypotheses,baseline=self.prepare(root)
                if fault=='audio':(root/'short.wav').write_bytes(b'changed audio')
                elif fault=='hypothesis':(hypotheses/'short.rttm').write_bytes(b'changed hypothesis')
                else:
                    path=hypotheses/'short.stats.json';stats=json.loads(path.read_text())
                    if fault=='stats_audio':stats['audio_sha256']='b'*64
                    elif fault=='stats_fixture':stats['fixture_id']='other-fixture'
                    else:del stats['hypothesis_rttm_sha256']
                    write_json(path,stats)
                report=helper.evaluate(manifest,hypotheses,baseline)
                self.assertEqual(report['status'],'incomplete')
                self.assertIsNone(report['aggregate'])
                self.assertIsNone(report['comparison'])
                self.assertEqual(report['fixtures'][0]['reason_code'],expected)
        with tempfile.TemporaryDirectory() as directory:
            manifest,hypotheses,baseline=self.prepare(Path(directory))
            original=json.loads(baseline.read_text());original['fixtures'][0]['id']='different-fixture'
            write_json(baseline,original)
            with self.assertRaisesRegex(helper.Rejected,'baseline_fixture_set_mismatch'):
                helper.evaluate(manifest,hypotheses,baseline)

    def test_failed_probe_with_stale_rttm_never_scores_success_or_leaks_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            manifest,hypotheses,baseline=self.prepare(root)
            path=hypotheses/'short.stats.json';stats=json.loads(path.read_text())
            stats.update(status='failed',error='C:/DO_NOT_COPY/sensitive-diagnostics')
            write_json(path,stats)
            output=root/'result.json'
            capture=io.StringIO()
            with contextlib.redirect_stdout(capture):
                exit_code=helper.main(['--manifest',str(manifest),'--hypotheses-dir',str(hypotheses),
                                       '--baseline',str(baseline),'--output',str(output)])
            report=json.loads(output.read_text())
            self.assertEqual(exit_code,2)
            self.assertEqual(report['status'],'incomplete')
            self.assertIsNone(report['aggregate'])
            self.assertEqual(report['fixtures'][0]['reason_code'],'probe_not_completed')
            self.assertNotIn('score',report['fixtures'][0])
            self.assertNotIn('DO_NOT_COPY',output.read_text()+capture.getvalue())
            self.assertNotIn(directory,output.read_text()+capture.getvalue())


if __name__=='__main__':
    unittest.main()
