"""Pure checks for the Whisper language-id cut. No model files."""
import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).resolve().parents[1] / "distill_whisper_lid.py"
SPEC = importlib.util.spec_from_file_location("distill_whisper_lid", MODULE)
distill = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(distill)


class DistillWhisperLidTests(unittest.TestCase):
    def test_metadata_puts_languages_on_consecutive_logits(self):
        meta = distill.language_id_metadata(["ru", "en", "de"])
        self.assertEqual(meta["n_vocab"], "3")
        self.assertEqual(meta["sot"], "0")
        self.assertEqual(meta["all_language_tokens"], "0,1,2")
        self.assertEqual(meta["all_language_codes"], "ru,en,de")

    def test_rejects_blank_duplicate_and_comma_codes(self):
        for codes in (["ru", "ru"], ["ru", ""], ["ru", "en us"], ["a,b"]):
            with self.assertRaises(ValueError):
                distill.language_id_metadata(codes)

    def test_language_rows_follow_token_order_and_sot_stays_separate(self):
        rows = [(index, index + 10) for index in range(6)]
        sot, languages = distill.slice_token_rows(rows, token_ids=[4, 1, 3], sot_id=2)
        self.assertEqual(sot, (2, 12))
        self.assertEqual(languages, [(4, 14), (1, 11), (3, 13)])

    def test_rejects_ids_outside_the_embedding(self):
        rows = [(0,), (1,)]
        with self.assertRaises(ValueError):
            distill.slice_token_rows(rows, token_ids=[0], sot_id=5)
        with self.assertRaises(ValueError):
            distill.slice_token_rows(rows, token_ids=[3], sot_id=0)
        with self.assertRaises(ValueError):
            distill.slice_token_rows(rows, token_ids=[0, 0], sot_id=1)
