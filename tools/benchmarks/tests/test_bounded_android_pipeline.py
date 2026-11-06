import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bounded_android_pipeline import (assess_step, parse_guard_metrics,
                                      parse_time_metrics, validate_budget, validate_plan)


class BoundedPipelineTests(unittest.TestCase):
    def setUp(self):
        self.plan = {"steps": [{"name": "asr-1", "kind": "asr", "argv": ["bin/model", "hello world"], "audio_seconds": 1.5}]}
        self.guard = {"model_exit_code": 0, "memory_rejected": 0, "timed_out": 0,
                      "samples": 3, "sampled_peak_tree_rss_kib": 100,
                      "sampled_peak_tree_pss_kib": 90, "peak_tree_sum_vmhwm_kib": 105,
                      "supervisor_peak_vmhwm_kib": 5, "pss_complete_samples": 3,
                      "remaining_owned_processes": 0}
        self.native = {"native_peak_rss_kib": 100, "native_wall_seconds": .3}

    def test_exact_budget_boundary_is_accepted(self):
        result = assess_step(self.guard, self.native, 105 * 1024 + 123, 123)
        self.assertEqual(result["status"], "accepted")
        self.assertTrue(result["native_budget_check_passed"])

    def test_one_byte_over_budget_is_rejected(self):
        result = assess_step(self.guard, self.native, 105 * 1024 + 122, 123)
        self.assertEqual(result["status"], "memory_rejected")
        self.assertFalse(result["accepted"])

    def test_native_hwm_catches_transient_missed_by_samples(self):
        self.native["native_peak_rss_kib"] = 200
        result = assess_step(self.guard, self.native, 150 * 1024, 0)
        self.assertEqual(result["status"], "memory_rejected")
        self.assertFalse(result["native_budget_check_passed"])

    def test_killed_or_failed_step_never_accepted(self):
        for field in ("memory_rejected", "model_exit_code", "timed_out", "remaining_owned_processes"):
            with self.subTest(field=field):
                guard = dict(self.guard, **{field: 1})
                result = assess_step(guard, self.native, 1_000_000, 0)
                self.assertFalse(result["accepted"])

    def test_invalid_budgets(self):
        for budget, reserve in ((0, 0), (-1, 0), (True, 0), (2**63, 0), (100, -1), (100, 100), (100, 101), (100, False)):
            with self.subTest(budget=budget, reserve=reserve), self.assertRaises(ValueError):
                validate_budget(budget, reserve)

    def test_valid_plan_preserves_literal_arguments(self):
        self.plan["steps"][0]["argv"] += ["$(do-not-execute)", "'quoted'", ""]
        self.assertEqual(validate_plan(self.plan), self.plan["steps"])

    def test_invalid_plans(self):
        invalid = [{}, {"steps": []}, {"steps": "bad"}, {"steps": [{}]}, {"steps": [dict(self.plan["steps"][0], name="../bad")]},
                   {"steps": self.plan["steps"] * 2}, {"steps": [dict(self.plan["steps"][0], argv="bad")]},
                   {"steps": [dict(self.plan["steps"][0], argv=[""])]}, {"steps": [dict(self.plan["steps"][0], argv=["a\0b"])]}]
        for duration in (-1, 0, float("inf"), float("nan"), True, "3"):
            plan = copy.deepcopy(self.plan)
            plan["steps"][0]["audio_seconds"] = duration
            invalid.append(plan)
        for plan in invalid:
            with self.subTest(plan=plan), self.assertRaises(ValueError):
                validate_plan(plan)

    def test_parse_outer_time_report(self):
        text = "Real time (s): 1.0\nMax RSS (KiB): 12\nmodel logs\nReal time (s): 3.125\nMax RSS (KiB): 456\n"
        self.assertEqual(parse_time_metrics(text), {"native_peak_rss_kib": 456, "native_wall_seconds": 3.125})
        with self.assertRaises(ValueError):
            parse_time_metrics("Elapsed seconds: 1.2")

    def test_guard_metrics_fail_closed(self):
        text = "\n".join(f"{key}={value}" for key, value in self.guard.items())
        self.assertEqual(parse_guard_metrics(text), self.guard)
        for bad in ("samples=1", text + "\nsamples=4", text.replace("samples=3", "samples=0"), text + "\nunknown=NaN"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                parse_guard_metrics(bad)


if __name__ == "__main__":
    unittest.main()
