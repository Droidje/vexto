"""Regression: Windows venv launchers must not hide worker RSS or orphan children."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / "run_community1_probe.py"
SPEC = importlib.util.spec_from_file_location("community1_probe", SOURCE)
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class ProcessTreeRegressionTests(unittest.TestCase):
    def test_launcher_rss_does_not_replace_interpreter_rss(self):
        class FakeSampler:
            def __init__(self, pid): self.pid = pid
            def sample(self): return {10: (5, 8), 11: (80, 120), 12: (10, 20)}[self.pid]
            def close(self): pass
        with patch.object(probe, "process_parents", return_value={10:1,11:10,12:11,99:1}), \
             patch.object(probe, "MemorySampler", FakeSampler):
            tree = probe.ProcessTreeSampler(10)
            self.assertEqual(tree.sample(), (95, 120, 148))
            self.assertEqual(set(tree.samplers), {10,11,12})
            tree.close()

    @unittest.skipUnless(os.name == "nt", "Windows venv launcher/process cleanup regression")
    def test_real_child_memory_and_timeout_cleanup_without_ml(self):
        actual_popen = subprocess.Popen
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp); ready = work / "child.pid"
            child = "import os,time,pathlib; data=bytearray(32*1024*1024); pathlib.Path(" + repr(str(ready)) + ").write_text(str(os.getpid())); time.sleep(10)"
            parent = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c'," + repr(child) + "]); time.sleep(10)"
            def launch(_command, **kwargs):
                return actual_popen([sys.executable, "-c", parent], **kwargs)
            args = SimpleNamespace(work_dir=work, audio=work/"not-read.wav", extra_python_path=[],
                                   timeout_seconds=1.2, memory_limit_bytes=0, batch_size=32)
            with patch.object(probe, "verify_snapshot", return_value=[]), \
                 patch.object(probe.subprocess, "Popen", side_effect=launch):
                report = probe.run(args, work/"unused-snapshot")
            self.assertEqual(report["status"], "timeout")
            self.assertGreaterEqual(report["monitored_process_count"], 2)
            self.assertGreater(report["observed_peak_rss_bytes"], 32*1024*1024)
            self.assertTrue(ready.exists(), "The real allocation child must have started")
            child_pid = int(ready.read_text())
            deadline = time.monotonic()+2
            while child_pid in probe.process_parents() and time.monotonic()<deadline:
                time.sleep(0.02)
            self.assertNotIn(child_pid, probe.process_parents(), "Timeout orphaned the allocation child")


if __name__ == "__main__":
    unittest.main()
