import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from review_asr import asr_text, fragments, validate_review


class ReviewAsrTests(unittest.TestCase):
    def fragment(self):
        return {"fragment_id": 1, "primary": "Alpha beta gamma", "secondary": "Alpha delta gamma"}

    def response(self, punctuation="Alpha, beta gamma!", replacements=None):
        return json.dumps({"punctuated_text": punctuation, "replacements": replacements or []})

    def test_punctuation_only_preserves_words_and_case(self):
        result, audit = validate_review(self.fragment(), self.response())
        self.assertEqual(result["punctuated_text"], "Alpha, beta gamma!")
        self.assertTrue(audit["punctuation_accepted"])
        self.assertFalse(audit["raw_asr_rewritten"])

    def test_lexical_replacement_is_proposal_only(self):
        proposals = [{"original": "beta", "replacement": "delta", "reason": "Alternative ASR differs"}]
        result, audit = validate_review(self.fragment(), self.response(replacements=proposals))
        self.assertEqual(result["replacement_proposals"], proposals)
        self.assertEqual(audit["proposals_applied"], 0)
        self.assertIn("beta", result["punctuated_text"])

    def test_case_words_reordering_and_new_symbols_are_rejected(self):
        for punctuation in ("alpha beta gamma", "Alpha delta gamma", "gamma beta Alpha", "Alpha beta gamma 😀"):
            with self.subTest(punctuation=punctuation):
                result, audit = validate_review(self.fragment(), self.response(punctuation))
                self.assertIsNone(result["punctuated_text"])
                self.assertFalse(audit["punctuation_accepted"])

    def test_nonliteral_and_partial_word_proposals_dropped(self):
        proposals = [{"original": "bet", "replacement": "delta", "reason": "partial"},
                     {"original": "absent", "replacement": "delta", "reason": "invented"}]
        result, audit = validate_review(self.fragment(), self.response(replacements=proposals))
        self.assertEqual(result["replacement_proposals"], [])
        self.assertEqual(len(audit["rejections"]), 2)

    def test_fragments_are_bounded_and_not_exhaustive(self):
        original = " ".join("word" + str(i) for i in range(300))
        alternative = original.replace("word100", "different100").replace("word200", "different200")
        result = fragments(original, alternative, words=20, maximum=1)
        self.assertEqual(len(result), 1)
        self.assertLessEqual(result[0]["primary_word_count"], 20)
        self.assertLessEqual(result[0]["secondary_word_count"], 20)
        self.assertNotEqual(result[0]["primary"], original)

    def test_full_asr_parsing_and_malformed_model(self):
        self.assertEqual(asr_text('logs\n{"text":"Alpha beta"}\n'), "Alpha beta")
        self.assertEqual(asr_text('{"transcription":[{"text":"Alpha"},{"text":"beta"}]}'), "Alpha beta")
        result, audit = validate_review(self.fragment(), '{"punctuated_text":"x","punctuated_text":"y","replacements":[]}')
        self.assertIsNotNone(audit["parse_error"])
        self.assertIsNone(result["punctuated_text"])


if __name__ == "__main__":
    unittest.main()
