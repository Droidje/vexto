"""Analytical synthetic intervals only; no private data or model inference."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'score_diarization.py'
SPEC = importlib.util.spec_from_file_location('score_diarization', MODULE)
scorer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scorer)


class DiarizationScoreTests(unittest.TestCase):
    def test_globally_permuted_labels_have_zero_der(self):
        ref = [(0, 1, 'A'), (1, 2, 'B'), (2, 3, 'A')]
        hyp = [(0, 1, 'Y'), (1, 2, 'X'), (2, 3, 'Y')]
        result = scorer.score(ref, hyp, 3)
        self.assertEqual(result['der'], 0)
        self.assertEqual(result['mapping_hypothesis_to_reference'], {'X':'B', 'Y':'A'})

    def test_overlap_scored_with_speaker_time_denominator(self):
        ref = [(0, 2, 'A'), (1, 3, 'B')]
        hyp = [(0, 2, 'X'), (1, 3, 'Y')]
        result = scorer.score(ref, hyp, 3)
        self.assertEqual(result['reference_speaker_seconds'], 4)
        self.assertEqual(result['der'], 0)

    def test_missing_voice_during_overlap(self):
        result = scorer.score([(0, 2, 'A'), (0, 2, 'B')], [(0, 2, 'X')], 2)
        self.assertEqual(result['miss_speaker_seconds'], 2)
        self.assertEqual(result['confusion_speaker_seconds'], 0)
        self.assertEqual(result['der'], .5)

    def test_false_alarm_on_reference_silence(self):
        result = scorer.score([(0, 1, 'A')], [(0, 1, 'X'), (2, 3, 'X')], 3)
        self.assertEqual(result['false_alarm_speaker_seconds'], 1)
        self.assertEqual(result['der'], 1)

    def test_one_global_mapping_counts_confusion(self):
        result = scorer.score([(0, 1, 'A'), (1, 2, 'B')], [(0, 2, 'X')], 2)
        self.assertEqual(result['miss_speaker_seconds'], 0)
        self.assertEqual(result['false_alarm_speaker_seconds'], 0)
        self.assertEqual(result['confusion_speaker_seconds'], 1)
        self.assertEqual(result['der'], .5)

    def test_exact_mapping_beats_greedy_pair_selection(self):
        ref = [(0, 7, 'A'), (7, 10, 'B')]
        hyp = [(0, 4, 'X'), (4, 7, 'Y'), (7, 10, 'X')]
        result = scorer.score(ref, hyp, 10)
        self.assertEqual(result['mapping_hypothesis_to_reference'], {'X':'B', 'Y':'A'})
        self.assertEqual(result['confusion_speaker_seconds'], 4)
        self.assertEqual(result['der'], .4)

    def test_zero_reference_is_explicitly_undefined(self):
        for hyp in ([], [(0, 1, 'X')]):
            with self.subTest(hyp=hyp):
                result = scorer.score([], hyp, 2)
                self.assertIsNone(result['der'])
                self.assertEqual(result['status'], 'undefined_zero_reference')
                self.assertEqual(result['false_alarm_speaker_seconds'], len(hyp))
                json.dumps(result, allow_nan=False)

    def test_empty_or_comment_only_rttm_is_empty_hypothesis(self):
        for text in ('', '  \n', '# no detected speech\n'):
            with self.subTest(text=text):
                rows, _ = scorer.parse_hypothesis(text)
                self.assertEqual(rows, [])

    def test_overlapping_duplicate_label_counts_once(self):
        result = scorer.score([(0, 3, 'A')], [(0, 2, 'X'), (1, 3, 'X'), (1, 3, 'X')], 3)
        self.assertEqual(result['reference_speaker_seconds'], 3)
        self.assertEqual(result['der'], 0)

    def test_uem_clips_outside_intervals_and_unused_labels(self):
        result = scorer.score([(0, 5, 'A')], [(0, 5, 'X')] + [(6, 7, str(n)) for n in range(9)], 2)
        self.assertEqual(result['reference_speaker_seconds'], 2)
        self.assertEqual(result['hypothesis_labels'], ['X'])
        self.assertEqual(result['der'], 0)

    def test_invalid_intervals_and_uem(self):
        for start, end in [(-1, 2), (0, 0), (2, 1), (0, float('inf')), (float('nan'), 1), (True, 2)]:
            with self.subTest(start=start, end=end), self.assertRaises(ValueError):
                scorer.score([(start, end, 'A')], [], 3)
        for duration in (0, -1, float('nan'), float('inf'), True):
            with self.subTest(duration=duration), self.assertRaises(ValueError):
                scorer.score([], [], duration)

    def test_rttm_duration_and_single_recording_validation(self):
        text = 'SPEAKER example 1 0.5 1.0 <NA> <NA> voice <NA> <NA>\n'
        segments, info = scorer.parse_rttm(text)
        self.assertEqual(segments, [(.5, 1.5, 'voice')])
        self.assertEqual(info['recording'], 'example')
        parsed_hyp, _ = scorer.parse_hypothesis('# independent metadata\n' + text)
        self.assertEqual(parsed_hyp, segments)
        for bad in (text.replace('0.5 1.0', '0.5 -1.0'), text + text.replace('example', 'other'),
                    text + text.replace('example 1', 'example 2'), 'unrecognized garbage'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                scorer.parse_rttm(bad)

    def test_legacy_sherpa_and_json(self):
        text = 'runtime diagnostic\n0.0 -- 1.5 speaker_0\n1.5 -- 2.0 speaker_1\n'
        segments, info = scorer.parse_hypothesis(text)
        self.assertEqual(segments, [(0, 1.5, 'speaker_0'), (1.5, 2, 'speaker_1')])
        self.assertEqual(info['ignored_diagnostic_lines'], 1)
        rows, _ = scorer.parse_hypothesis('[{"start_s":0,"end_s":1,"speaker":"X"}]')
        self.assertEqual(rows, [(0, 1, 'X')])
        for bad in ('runtime diagnostic only', '-1 -- 2 speaker_0', '0 -- nan speaker_0'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                scorer.parse_hypothesis(bad)

    def test_rejects_more_than_eight_labels(self):
        with self.assertRaises(ValueError):
            scorer.score([(0, 1, str(i)) for i in range(9)], [], 1)

    def test_cli_records_hashes_and_refuses_input_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ref, hyp, out = [root / name for name in ('ref.rttm', 'hyp.txt', 'score.json')]
            ref.write_text('SPEAKER example 1 0 1 <NA> <NA> A <NA> <NA>\n', encoding='utf-8')
            hyp.write_text('0 -- 1 X\n', encoding='utf-8')
            args = ['--reference', str(ref), '--hypothesis', str(hyp), '--duration', '1']
            self.assertEqual(scorer.main(args + ['--output', str(out)]), 0)
            result = json.loads(out.read_text())
            self.assertEqual(result['der'], 0)
            self.assertEqual(len(result['inputs']['reference']['sha256']), 64)
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                scorer.main(args + ['--output', str(ref)])


if __name__ == '__main__':
    unittest.main()
