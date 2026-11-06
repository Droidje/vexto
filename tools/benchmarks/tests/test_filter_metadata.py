"""Synthetic fixtures only; no private recordings or transcripts."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'filter_metadata.py'
SPEC = importlib.util.spec_from_file_location('filter_metadata', MODULE)
metadata = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(metadata)


def response(keywords):
    return json.dumps({'keywords': keywords}, ensure_ascii=False)


class FilterMetadataTests(unittest.TestCase):
    def test_valid_literal_phrases_preserve_source_and_order(self):
        asr = {'text': '\nЁж изучает новый проект и сроки проверки.\t'}
        original = dict(asr)
        result, audit = metadata.filter_metadata(asr, response(['новый проект', 'Ёж', 'сроки проверки']))
        self.assertEqual(result, {'keywords': ['новый проект', 'Ёж', 'сроки проверки']})
        self.assertEqual(asr, original)
        self.assertTrue(audit['model_output_fully_valid'])

    def test_invalid_phrase_is_removed_without_losing_valid_neighbors(self):
        result, audit = metadata.filter_metadata({'text': 'план релиза и сроки проверки'},
            response(['план релиза', 'новый план выпуска на следующей неделе', 'сроки проверки']))
        self.assertEqual(result['keywords'], ['план релиза', 'сроки проверки'])
        self.assertEqual(audit['rejected'][0]['index'], 1)
        self.assertEqual(audit['rejected'][0]['reasons'], ['word_count_out_of_range', 'not_literal_phrase'])
        self.assertFalse(audit['model_output_fully_valid'])
        self.assertEqual(audit['status'], 'filtered')

    def test_unicode_word_edges_reject_partial_words_and_marks(self):
        for raw, phrase in [('перепланирование', 'план'), ('ёжик', 'ёж'),
                            ('cafe\u0301', 'cafe'), ('план_релиза', 'план'), ('план2', 'план')]:
            with self.subTest(raw=raw):
                result, audit = metadata.filter_metadata({'text': raw}, response([phrase]))
                self.assertEqual(result['keywords'], [])
                self.assertIn('not_literal_phrase', audit['rejected'][0]['reasons'])

    def test_later_complete_match_is_allowed_after_partial_match(self):
        result, _ = metadata.filter_metadata({'text': 'ёжик, а дальше ёж.'}, response(['ёж']))
        self.assertEqual(result['keywords'], ['ёж'])

    def test_case_and_internal_whitespace_are_not_normalized(self):
        result, audit = metadata.filter_metadata({'text': 'Новый\tпроект и новый  проект'},
            response(['новый проект', 'новый  проект', 'Новый\tпроект', 'НОВЫЙ']))
        self.assertEqual(result['keywords'], ['новый  проект', 'Новый\tпроект'])
        self.assertEqual(len(audit['rejected']), 2)

    def test_one_to_five_words_and_punctuation(self):
        raw = 'один два три четыре пять шесть; (готовый проект).'
        result, audit = metadata.filter_metadata({'text': raw},
            response(['один', 'один два три четыре пять', 'один два три четыре пять шесть', 'готовый проект']))
        self.assertEqual(result['keywords'], ['один', 'один два три четыре пять', 'готовый проект'])
        self.assertEqual(audit['rejected'][0]['reasons'], ['word_count_out_of_range'])

    def test_empty_nonstrings_and_duplicates_are_audited(self):
        result, audit = metadata.filter_metadata({'text': 'план релиза'},
            response(['', ' \t', None, 2, True, 'план релиза', ' план релиза ']))
        self.assertEqual(result['keywords'], ['план релиза'])
        self.assertEqual([item['reasons'] for item in audit['rejected']],
                         [['empty'], ['empty'], ['not_string'], ['not_string'], ['not_string'], ['duplicate']])
        self.assertEqual(len(audit['normalizations']), 2)

    def test_maximum_three_valid_phrases(self):
        result, audit = metadata.filter_metadata({'text': 'альфа бета гамма дельта'},
            response(['альфа', 'бета', 'гамма', 'дельта']))
        self.assertEqual(result['keywords'], ['альфа', 'бета', 'гамма'])
        self.assertEqual(audit['rejected'][0]['reasons'], ['keyword_limit_exceeded'])

    def test_punctuation_only_is_not_a_word(self):
        result, audit = metadata.filter_metadata({'text': '...'}, response(['...']))
        self.assertEqual(result['keywords'], [])
        self.assertEqual(audit['rejected'][0]['reasons'], ['word_count_out_of_range'])

    def test_optional_runtime_suffix_and_json_whitespace(self):
        for suffix in ['', '\n[end of text]', '\r\n[end of text]\t\n']:
            with self.subTest(suffix=suffix):
                result, audit = metadata.filter_metadata({'text': 'план'}, ' \n'+response(['план'])+suffix)
                self.assertEqual(result['keywords'], ['план'])
                self.assertEqual(audit['runtime_suffix_removed'], bool(suffix))

    def test_extra_payload_or_thinking_rejects_whole_response(self):
        valid = response(['план'])
        for bad in ['<think>reasoning</think>'+valid, '```json\n'+valid+'\n```',
                    valid+valid, valid+' trailing', valid+'[end of text][end of text]',
                    '[end of text]'+valid, valid+'\v']:
            with self.subTest(bad=bad):
                result, audit = metadata.filter_metadata({'text': 'план'}, bad)
                self.assertEqual(result['keywords'], [])
                self.assertIn('fatal_error', audit)

    def test_schema_duplicates_and_nonstandard_json_fail_closed(self):
        for bad in ['[]', '{}', '{"keywords":"план"}', '{"keywords":[],"text":"extra"}',
                    '{"keywords":[],"keywords":["план"]}', '{"keywords":[NaN]}',
                    '{"keywords":[Infinity]}', '{"keywords":["план",]}']:
            with self.subTest(bad=bad):
                result, audit = metadata.filter_metadata({'text': 'план'}, bad)
                self.assertEqual(result['keywords'], [])
                self.assertIn('fatal_error', audit)

    def test_empty_metadata_is_valid_but_not_a_claim_of_relevance(self):
        result, audit = metadata.filter_metadata({'text': ''}, response([]))
        self.assertEqual(result, {'keywords': []})
        self.assertTrue(audit['model_output_fully_valid'])
        self.assertTrue(audit['limitations'])

    def test_invalid_asr_is_not_used(self):
        for asr in [None, [], {}, {'text': 4}]:
            with self.subTest(asr=asr), self.assertRaises(ValueError):
                metadata.filter_metadata(asr, response([]))

    def test_cli_separate_audit_hashes_and_immutable_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, model, output = [root/name for name in ('asr.json', 'model.txt', 'result.json')]
            source.write_bytes(b'\xef\xbb\xbf'+json.dumps({'text': 'план релиза'}).encode())
            model.write_bytes((response(['план релиза', 'новый релиз'])+'\n[end of text]').encode())
            before = [source.read_bytes(), model.read_bytes()]
            self.assertEqual(metadata.main(['--asr-json', str(source), '--model-output', str(model),
                                            '--output', str(output)]), 0)
            self.assertEqual(json.loads(output.read_bytes()), {'keywords': ['план релиза']})
            audit = json.loads((root/'result.audit.json').read_bytes())
            self.assertFalse(audit['model_output_fully_valid'])
            self.assertEqual(set(audit['input_sha256']), {'asr_json', 'model_output'})
            self.assertEqual([source.read_bytes(), model.read_bytes()], before)

    def test_cli_malformed_model_writes_empty_result_and_failure_audit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, model, output, audit = [root/name for name in ('asr.json', 'model.txt', 'out.json', 'audit.json')]
            source.write_text('{"text":"sample"}')
            model.write_text('not json')
            self.assertEqual(metadata.main(['--asr-json', str(source), '--model-output', str(model),
                                            '--output', str(output), '--audit', str(audit)]), 2)
            self.assertEqual(json.loads(output.read_bytes()), {'keywords': []})
            self.assertEqual(json.loads(audit.read_bytes())['status'], 'rejected')

    def test_cli_rejects_output_aliases_to_inputs_and_each_other(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, model, output = [root/name for name in ('asr.json', 'model.txt', 'out.json')]
            source.write_text('{"text":"sample"}')
            model.write_text('{"keywords":[]}')
            before = [source.read_bytes(), model.read_bytes()]
            base = ['--asr-json', str(source), '--model-output', str(model)]
            variants = [['--output', str(source)], ['--output', str(model)],
                        ['--output', str(output), '--audit', str(source)],
                        ['--output', str(output), '--audit', str(output)]]
            alias = root/'hardlink.json'
            try:
                os.link(source, alias)
            except OSError:
                pass
            else:
                variants.append(['--output', str(alias)])
            for variant in variants:
                with self.subTest(variant=variant), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as rejected:
                        metadata.main(base+variant)
                    self.assertEqual(rejected.exception.code, 2)
            self.assertEqual([source.read_bytes(), model.read_bytes()], before)


if __name__ == '__main__':
    unittest.main()
