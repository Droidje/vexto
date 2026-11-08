import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[3] / "scripts/publish_phone_oci.py"
spec = importlib.util.spec_from_file_location("publish_phone_oci", SCRIPT)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublishPhoneOciTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / publisher.APK_NAME
        self.apk.write_bytes(b"test-signed-offline-apk")
        self.size, self.digest = publisher.file_identity(self.apk)
        self.sha = "a" * 40
        self.repository = "ghcr.io/droidje/vexto-offline"
        self.manifest = {
            "schemaVersion": 2, "artifactType": publisher.ARTIFACT_TYPE,
            "layers": [{"mediaType": publisher.APK_TYPE, "size": self.size,
                        "digest": "sha256:" + self.digest,
                        "annotations": {"org.opencontainers.image.title": publisher.APK_NAME}}],
            "annotations": {"org.opencontainers.image.source": publisher.SOURCE,
                            "org.opencontainers.image.revision": self.sha,
                            "io.vexto.apk.sha256": self.digest,
                            "io.vexto.apk.size": str(self.size), "io.vexto.ci.run-id": "123"},
        }

    def validate(self):
        return publisher.validate_manifest(json.dumps(self.manifest).encode(), size=self.size,
                                           sha256=self.digest, source_sha=self.sha)

    def publish(self, promote=False):
        return publisher.publish(self.apk, self.repository, self.sha, "123", ["oras"], promote,
                                 self.sha if promote else None)

    def test_hashing_is_bounded_and_matches_the_entire_file(self):
        class Bounded(io.BytesIO):
            def read(self, size=-1):
                if size != 1024 * 1024:
                    raise AssertionError("APK must not be read into memory in one operation")
                return super().read(size)
        payload = b"a" * (3 * 1024 * 1024 + 19)
        with patch.object(Path, "open", return_value=Bounded(payload)):
            self.assertEqual((len(payload), hashlib.sha256(payload).hexdigest()),
                             publisher.file_identity(self.apk))

    def test_one_raw_apk_layer_is_accepted(self):
        self.assertEqual(self.manifest, self.validate())

    def test_tar_wrapping_and_descriptor_drift_are_rejected(self):
        for field, value in [("mediaType", "application/vnd.oci.image.layer.v1.tar"),
                             ("size", self.size + 1), ("digest", "sha256:" + "b" * 64)]:
            with self.subTest(field=field):
                original = self.manifest["layers"][0][field]
                self.manifest["layers"][0][field] = value
                with self.assertRaises(ValueError): self.validate()
                self.manifest["layers"][0][field] = original

    def test_provenance_and_extra_layers_are_rejected(self):
        self.manifest["annotations"]["org.opencontainers.image.revision"] = "b" * 40
        with self.assertRaises(ValueError): self.validate()
        self.manifest["annotations"]["org.opencontainers.image.revision"] = self.sha
        self.manifest["layers"].append(self.manifest["layers"][0])
        with self.assertRaises(ValueError): self.validate()

    def test_existing_immutable_tag_is_reused_without_reupload(self):
        raw = json.dumps(self.manifest).encode()
        with patch.object(publisher, "fetch_manifest", return_value=raw), patch.object(subprocess, "run") as run:
            result = self.publish()
        self.assertTrue(result["reused"])
        self.assertFalse(result["latest"])
        run.assert_not_called()

    def test_existing_wrong_immutable_tag_is_not_overwritten(self):
        self.manifest["layers"][0]["size"] += 1
        with patch.object(publisher, "fetch_manifest", return_value=json.dumps(self.manifest).encode()), \
                patch.object(subprocess, "run") as run:
            with self.assertRaises(ValueError): self.publish(promote=True)
        run.assert_not_called()

    def test_upload_is_checked_before_latest_is_promoted(self):
        raw = json.dumps(self.manifest).encode()
        with patch.object(publisher, "fetch_manifest", side_effect=[None, raw, raw]), \
                patch.object(subprocess, "run") as run, \
                patch.object(publisher, "current_main_sha", return_value=self.sha):
            result = self.publish(promote=True)
        self.assertEqual("push", run.call_args_list[0].args[0][1])
        self.assertIn(publisher.APK_NAME + ":" + publisher.APK_TYPE, run.call_args_list[0].args[0])
        self.assertEqual(["oras", "tag", self.repository + "@sha256:" + hashlib.sha256(raw).hexdigest(), "latest"],
                         run.call_args_list[1].args[0])
        self.assertTrue(result["latest"])
        self.assertEqual("promoted", result["promotion"])

    def test_main_is_rechecked_after_the_upload_before_promotion(self):
        raw = json.dumps(self.manifest).encode()
        with patch.object(publisher, "fetch_manifest", side_effect=[None, raw]) as fetch, \
                patch.object(subprocess, "run") as run:
            def main_after_upload():
                self.assertEqual(2, fetch.call_count)
                self.assertEqual(1, run.call_count)
                return "b" * 40
            with patch.object(publisher, "current_main_sha", side_effect=main_after_upload):
                result = self.publish(promote=True)
        self.assertFalse(result["latest"])
        self.assertEqual("skipped-main-changed", result["promotion"])
        self.assertEqual(1, run.call_count)
        self.assertEqual("push", run.call_args.args[0][1])

    def test_latest_requires_explicit_expected_main_before_upload(self):
        with patch.object(publisher, "fetch_manifest") as fetch:
            with self.assertRaises(ValueError):
                publisher.publish(self.apk, self.repository, self.sha, "123", ["oras"], True)
        fetch.assert_not_called()

    def test_failed_upload_does_not_promote_latest(self):
        with patch.object(publisher, "fetch_manifest", return_value=None), \
                patch.object(subprocess, "run", side_effect=subprocess.CalledProcessError(1, "oras")) as run:
            with self.assertRaises(subprocess.CalledProcessError): self.publish(promote=True)
        self.assertEqual(1, run.call_count)

    def test_authentication_failure_is_not_mistaken_for_missing_artifact(self):
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"", b"unauthorized")):
            with self.assertRaises(RuntimeError): publisher.fetch_manifest(["oras"], "ref")


if __name__ == "__main__":
    unittest.main()
