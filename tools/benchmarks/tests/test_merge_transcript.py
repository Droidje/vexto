"""Synthetic fixtures only; no private recordings or transcripts."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'merge_transcript.py'
SPEC = importlib.util.spec_from_file_location('merge_transcript', MODULE)
merger = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(merger)


def asr(tokens, times, text=None):
    return {'text': ''.join(tokens).strip() if text is None else text,
            'tokens': tokens, 'timestamps': times}


class MergeTranscriptTests(unittest.TestCase):
    def test_speaker_change_uses_overlap_and_acoustic_order(self):
        result, provenance = merger.merge_transcript(
            asr(['Hello,', ' friend.', ' Bye!'], [0.1, 0.5, 1.0]),
            [{'start': .9, 'end': 2., 'speaker': 'z'},
             {'start': 0., 'end': .9, 'speaker': 'a'}])
        self.assertEqual([row['speaker_id'] for row in result['transcript']],
                         ['speaker_1', 'speaker_2'])
        self.assertEqual([row['text'] for row in result['transcript']], ['Hello, friend.', 'Bye!'])
        self.assertEqual(provenance['speaker_mapping'], {'a': 'speaker_1', 'z': 'speaker_2'})

    def test_word_crossing_boundary_uses_largest_not_start_overlap(self):
        result, _ = merger.merge_transcript(asr(['ab', 'cd'], [.9, 1.1]),
            [{'start': .0, 'end': .95, 'speaker': 'first'},
             {'start': .95, 'end': 2., 'speaker': 'second'}])
        self.assertEqual(result['transcript'][0]['speaker_id'], 'speaker_2')

    def test_no_overlap_and_exact_touch_are_unknown(self):
        result, _ = merger.merge_transcript(asr(['Wait.', ' Now.'], [0., 1.]),
            [{'start': .04, 'end': 1., 'speaker': 'a'}])
        self.assertEqual([row['speaker_id'] for row in result['transcript']], ['unknown', 'unknown'])

    def test_duplicate_diarization_does_not_double_count(self):
        result, _ = merger.merge_transcript(asr(['ab', 'cd'], [0., .96]),
            [{'start': 0., 'end': .4, 'speaker': 'a'},
             {'start': 0., 'end': .4, 'speaker': 'a'},
             {'start': .4, 'end': 1., 'speaker': 'b'}])
        self.assertEqual(result['transcript'][0]['speaker_id'], 'speaker_2')

    def test_ties_are_deterministic_and_recorded(self):
        result, provenance = merger.merge_transcript(asr(['One'], [1.]),
            [{'start': .5, 'end': 2., 'speaker': 'later'},
             {'start': 0., 'end': 2., 'speaker': 'earlier'}])
        self.assertEqual(result['transcript'][0]['speaker_id'], 'speaker_1')
        self.assertTrue(provenance['words'][0]['ambiguous'])

    def test_pause_threshold_splits_same_speaker_only_above_point_seven(self):
        for pause, expected in ((.7, 1), (.7001, 2)):
            with self.subTest(pause=pause):
                result, _ = merger.merge_transcript(asr(['One', ' two'], [0., .04 + pause]),
                    [{'start': 0., 'end': 5., 'speaker': 'a'}])
                self.assertEqual(len(result['transcript']), expected)

    def test_exact_source_and_lexical_sequence_are_preserved(self):
        text = '\n  Alpha,\t  beta!\nGamma?  '
        result, provenance = merger.merge_transcript(
            asr([' Alpha,', '\t  beta!', '\nGamma? '], [0., .3, 2.], text), [])
        self.assertEqual(provenance['raw_asr_text'], text)
        self.assertEqual([row['text'] for row in result['transcript']], ['Alpha,\t  beta!', 'Gamma?'])
        self.assertEqual([word for row in result['transcript'] for word in row['text'].split()], text.split())

    def test_leading_spaces_and_tokens_crossing_whitespace(self):
        result, provenance = merger.merge_transcript(asr(['  Red blue', ' green'], [1., 2.]), [])
        self.assertEqual([word['text'] for word in provenance['words']], ['Red', 'blue', 'green'])
        self.assertEqual([row['text'] for row in result['transcript']], ['Red blue', 'green'])

    def test_repeated_timestamps_and_empty_tokens_are_allowed(self):
        result, _ = merger.merge_transcript(asr(['', 'A', ' B'], [0., 0., 0.]), [])
        self.assertEqual(result['transcript'][0]['text'], 'A B')
        self.assertEqual(result['transcript'][0]['end_ms'], 40)

    def test_empty_transcript(self):
        result, provenance = merger.merge_transcript(asr([], [], ' \n'), [])
        self.assertEqual(result, {'transcript': []})
        self.assertEqual(provenance['raw_asr_text'], ' \n')

    def test_spoken_digits_do_not_determine_speaker_ids(self):
        result, provenance = merger.merge_transcript(asr(['2', ' 1'], [0., 1.]),
            [{'start': 1., 'end': 2., 'speaker': 'a'},
             {'start': 0., 'end': .5, 'speaker': 'z'}])
        self.assertEqual(provenance['speaker_mapping'], {'z': 'speaker_1', 'a': 'speaker_2'})
        self.assertEqual([row['speaker_id'] for row in result['transcript']], ['speaker_1', 'speaker_2'])

    def test_invalid_asr_fails_closed(self):
        invalid = [asr(['Wrong'], [0.], 'Right'), asr(['One'], []),
                   asr(['One', ' two'], [1., .5]),
                   {'text': 'One', 'tokens': 'One', 'timestamps': [0.]},
                   {'text': 2, 'tokens': [], 'timestamps': []}]
        invalid += [asr(['One'], [time]) for time in (-1, float('nan'), float('inf'), True, '1', 10**1000)]
        for fixture in invalid:
            with self.subTest(fixture=fixture), self.assertRaises(ValueError):
                merger.merge_transcript(fixture, [])

    def test_invalid_diarization_fails_closed(self):
        invalid = [{}, [None], [{'start': 0., 'end': 0., 'speaker': 'a'}],
                   [{'start': 0., 'end': 1., 'speaker': ''}],
                   [{'start': 0., 'end': float('nan'), 'speaker': 'a'}],
                   [{'start': True, 'end': 1., 'speaker': 'a'}]]
        for fixture in invalid:
            with self.subTest(fixture=fixture), self.assertRaises(ValueError):
                merger.merge_transcript(asr(['One'], [0.]), fixture)

    def test_cli_writes_contract_and_separate_provenance(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            source, diarization, output = [folder / name for name in ('asr.json', 'diar.json', 'result.json')]
            source.write_text(json.dumps(asr(['Hello.'], [.123])), encoding='utf-8')
            diarization.write_text('[]', encoding='utf-8')
            self.assertEqual(merger.main(['--asr-json', str(source), '--diarization-json', str(diarization),
                                          '--output', str(output)]), 0)
            result = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual(set(result), {'transcript'})
            self.assertEqual(result['transcript'][0], {'speaker_id': 'unknown', 'start_ms': 123,
                                                       'end_ms': 163, 'text': 'Hello.'})
            provenance = json.loads(output.with_name('result.provenance.json').read_text(encoding='utf-8'))
            self.assertEqual(provenance['raw_asr_text'], 'Hello.')
            self.assertEqual(len(provenance['input_sha256']['asr']), 64)

    def test_cli_rejects_bad_times_without_export_and_preserves_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            source, diarization, output = [folder / name for name in ('asr.json', 'diar.json', 'result.json')]
            raw = json.dumps(asr(['One'], [-1.]))
            source.write_text(raw, encoding='utf-8')
            diarization.write_text('[]', encoding='utf-8')
            arguments = ['--asr-json', str(source), '--diarization-json', str(diarization)]
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(merger.main(arguments + ['--output', str(output)]), 2)
                with self.assertRaises(SystemExit) as rejected:
                    merger.main(arguments + ['--output', str(source)])
            self.assertEqual(rejected.exception.code, 2)
            self.assertFalse(output.exists())
            self.assertEqual(source.read_text(encoding='utf-8'), raw)


if __name__ == '__main__':
    unittest.main()
