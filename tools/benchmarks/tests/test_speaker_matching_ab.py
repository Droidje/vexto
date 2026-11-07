import importlib.util
from pathlib import Path
import unittest


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / f"{name}.py")
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


ab = load("speaker_matching_ab")
audio = load("speaker_matching_audio")


class SpeakerMatchingAbTest(unittest.TestCase):
    def test_multi_sample_gallery_can_recover_unrepresentative_first_sample(self):
        query = [1., 0.]
        samples = [ab.normalize([.7, .714]), query, query]
        self.assertLess(ab.score(query, samples, "first"), .8)
        self.assertGreater(ab.score(query, samples, "mean"), .9)

    def test_calibration_can_abstain_without_claiming_an_unknown_identity(self):
        trials = [("a", [(.9, "a"), (.89, "b")]), ("b", [(.91, "b"), (.89, "a")])]
        threshold, margin = ab.calibrate(trials)
        result = ab.metrics(trials, threshold, margin)
        self.assertEqual(0, result["false_accept_unknown"])
        self.assertEqual(0, result["wrong_identity"])
        self.assertEqual(2, result["correct"])

    def test_tied_profiles_never_match_even_with_zero_margin(self):
        self.assertIsNone(ab.decision([(.95, "a"), (.95, "b")], .8, 0))

    def test_split_leakage_and_invalid_embeddings_are_rejected(self):
        rows = [dict(person_id="a", session_id="s1", local_id="1", split="calibration", embedding=[1., 0.]),
                dict(person_id="a", session_id="s2", local_id="1", split="test", embedding=[1., 0.])]
        with self.assertRaises(ValueError): ab.validate(rows)
        for vector in ([0., 0.], [float("nan"), 1.], []):
            with self.assertRaises(ValueError): ab.normalize(vector)

    def test_enrollment_does_not_include_query_session(self):
        rows = [dict(person_id=p, session_id=f"s{i}", local_id=p, split="test", embedding=v)
                for p, v in (("a", [1., 0.]), ("b", [0., 1.])) for i in range(4)]
        self.assertEqual(4, len(ab.trials(rows, "test", 2, "first")))
        self.assertEqual(2, len(ab.trials(rows, "test", 3, "mean")))
        with self.assertRaises(ValueError): ab.trials(rows, "test", 4, "mean")

    def test_windows_are_bounded_and_do_not_bridge_source_cuts(self):
        windows = audio.short_windows([[0, 10], [20, 50]])
        self.assertEqual(32, len(windows))
        self.assertEqual((0, 16000), windows[0])
        self.assertEqual((49 * 16000, 50 * 16000), windows[-1])
        self.assertTrue(all(b <= 10 * 16000 or a >= 20 * 16000 for a, b in windows))


if __name__ == "__main__":
    unittest.main()
