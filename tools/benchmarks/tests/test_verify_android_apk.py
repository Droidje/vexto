"""Packaging verification must work without the native builder's generated files."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import runpy
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from export_overlap_model import ENCODER_SHA, THRESHOLD


class VerifyAndroidApkTest(unittest.TestCase):
    def verify(self, *, change=None, corrupt=False, prepared=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ("tools/models/overlap-head.json", "tools/model-notices/overlap-wavlm-torchaudio-MIT.txt"):
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"fixture")
            model = b"overlap model"
            manifest = dict(version="natural-overlap-v1", path="overlap/wavlm.onnx",
                            sample_rate=16000, window_samples=64000, threshold=THRESHOLD,
                            encoder_sha256=ENCODER_SHA,
                            head_sha256=hashlib.sha256(b"fixture").hexdigest(),
                            bytes=len(model), sha256=hashlib.sha256(model).hexdigest())
            if prepared:
                target = root / "engine/src/main/overlap-assets/overlap/manifest.json"
                target.parent.mkdir(parents=True)
                target.write_text(json.dumps(manifest | {"bytes": 1}))
            manifest.update(change or {})
            # Collect required native names from the real verifier rather than
            # freezing a second copy of its ABI/library inventory in this test.
            source = (ROOT / "scripts/verify_android_apk.py").read_text()
            inventory = source.split('parser = argparse.ArgumentParser', 1)[0]
            modules = {}
            quality = types.ModuleType("prepare_quality_models")
            quality.ROOT, quality.MANIFEST = root, root / "unused.json"
            punctuation = types.ModuleType("prepare_punctuation_models")
            punctuation.ARTIFACTS = {}
            modules.update(prepare_quality_models=quality, prepare_punctuation_models=punctuation)
            with patch.dict(sys.modules, modules):
                namespace = {}
                exec(inventory, namespace)
                apk_path = root / "fixture.apk"
                with ZipFile(apk_path, "w") as apk:
                    for name in ("AndroidManifest.xml", "classes.dex"):
                        apk.writestr(name, b"fixture")
                    for abi in ("arm64-v8a", "x86_64"):
                        for library in namespace["LIBRARIES"]:
                            apk.writestr(f"lib/{abi}/{library}", b"fixture")
                    for library in ("nemotron", "whisper", "llama"):
                        apk.writestr(f"lib/x86_64/libvexto_{library}_avx2_exec.so", b"fixture")
                    for library in ("libnemo_speech_asr_avx2.so", "libnemo_speech_asr_c_avx2.so"):
                        apk.writestr("lib/x86_64/" + library, b"fixture")
                    apk.writestr("assets/overlap/manifest.json", json.dumps(manifest))
                    apk.writestr("assets/overlap/wavlm.onnx", b"corrupt model" if corrupt else model)
                    apk.writestr("assets/overlap/NOTICE.txt", b"fixture")
                with patch.object(sys, "argv", ["verify_android_apk.py", str(apk_path)]), contextlib.redirect_stdout(io.StringIO()):
                    runpy.run_path(str(ROOT / "scripts/verify_android_apk.py"), run_name="__main__")

    def test_packaging_runner_without_generated_manifest(self):
        self.verify()

    def test_changed_threshold_rejected(self):
        with self.assertRaisesRegex(SystemExit, "repository pins"):
            self.verify(change={"threshold": 0.1})

    def test_changed_head_rejected(self):
        with self.assertRaisesRegex(SystemExit, "repository pins"):
            self.verify(change={"head_sha256": "0" * 64})

    def test_corrupt_model_rejected(self):
        with self.assertRaisesRegex(SystemExit, "checksum"):
            self.verify(corrupt=True)

    def test_prepared_manifest_still_checked(self):
        with self.assertRaisesRegex(SystemExit, "prepared model"):
            self.verify(prepared=True)
