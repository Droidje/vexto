from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from urllib.parse import quote
from uuid import uuid4

from fastapi.testclient import TestClient

from backend.api import create_app
from backend.config import Settings


class RecordingApiTest(unittest.TestCase):
    AUDIO = b"synthetic audio payload"
    AUDIO_TYPE = "audio/mp4"
    REVISION = "X-Transcript-Revision"

    def setUp(self):
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.data = Path(self.directory.name)
        tokens = {"primary": "test-primary-workspace-token", "other": "test-secondary-workspace-token"}
        self.settings = Settings.from_workspaces(self.data, tokens, max_audio_bytes=64)
        self.primary = self.auth(tokens["primary"])
        self.other = self.auth(tokens["other"])
        self.client = TestClient(create_app(self.settings))
        self.addCleanup(self.client.close)
        self.recording = f"/v1/recordings/{uuid4()}"
        self.audio = self.recording + "/audio"
        self.transcript = self.recording + "/transcript"
        self.links = self.recording + "/speaker-links"

    @staticmethod
    def auth(token):
        return {"Authorization": "Bearer " + token}

    def upload(self, content=None, auth=None, path=None):
        if content is None:
            content = iter((self.AUDIO[:10], self.AUDIO[10:]))
        return self.client.put(path or self.audio, content=content,
                               headers={**(auth or self.primary), "Content-Type": self.AUDIO_TYPE})

    @staticmethod
    def transcript_with(*texts, label="speaker/1"):
        return {"transcript": [
            {"speaker_id": label, "start_ms": i * 1000, "end_ms": (i + 1) * 1000, "text": line}
            for i, line in enumerate(texts)
        ]}

    def test_streamed_upload_retry_conflict_download_and_restart(self):
        created = self.upload()
        self.assertEqual(created.status_code, 201, created.text)
        self.assertEqual(created.json()["id"], self.recording.rsplit("/", 1)[-1])
        self.assertEqual(self.upload(content=self.AUDIO).status_code, 200)
        self.assertEqual(self.upload(content=b"other bytes").status_code, 409)
        self.assertEqual(list((self.data / "staging").iterdir()), [])

        metadata = {
            "title": "Разговор",
            "duration_ms": 1700,
            "recorded_at": "2025-04-18T15:35:00+03:00",
            "location": {"latitude": 55.75, "longitude": 37.61},
            "filename": "Разговор.m4a",
            "mime_type": self.AUDIO_TYPE,
        }
        saved = self.client.put(self.recording + "/metadata", headers=self.primary, json=metadata)
        self.assertEqual(saved.status_code, 200, saved.text)
        orphan = self.data / "staging" / "upload-interrupted"
        orphan.write_bytes(b"partial upload")
        with TestClient(create_app(self.settings)) as restarted:
            self.assertFalse(orphan.exists())
            details = restarted.get(self.recording, headers=self.primary).json()
            self.assertEqual(details["recorded_at"], "2025-04-18T12:35:00Z")
            for field in ("title", "duration_ms", "location", "filename", "mime_type"):
                self.assertEqual(details[field], metadata[field])
            self.assertEqual(details["bytes"], len(self.AUDIO))
            self.assertFalse(details["has_transcript"])
            self.assertEqual(restarted.get("/v1/recordings", headers=self.primary).json(),
                             {"recordings": [details]})
            with restarted.stream("GET", self.audio, headers=self.primary) as download:
                self.assertEqual(download.status_code, 200)
                self.assertEqual(download.headers["Content-Type"], self.AUDIO_TYPE)
                self.assertEqual(download.headers["Content-Length"], str(len(self.AUDIO)))
                self.assertEqual(download.headers["Content-Disposition"],
                                 "attachment; filename*=UTF-8''" + quote(metadata["filename"]))
                self.assertEqual(download.headers["Cache-Control"], "no-store")
                self.assertEqual(b"".join(download.iter_bytes()), self.AUDIO)

    def test_workspace_isolation_for_recordings_and_speakers(self):
        self.assertEqual(self.client.get("/healthz").json(), {"status": "ok"})
        self.assertEqual(self.client.get("/v1/recordings").status_code, 401)
        self.assertEqual(self.client.get("/v1/recordings", headers=self.auth("unknown")).status_code, 401)
        self.assertEqual(self.upload().status_code, 201)
        for path in (self.recording, self.audio, self.transcript, self.links):
            self.assertEqual(self.client.get(path, headers=self.other).status_code, 404)
        self.assertEqual(self.client.get("/v1/recordings", headers=self.other).json(), {"recordings": []})
        self.assertEqual(self.client.delete(self.recording, headers=self.other).status_code, 404)
        self.assertEqual(self.upload(auth=self.other, content=b"independent").status_code, 201)
        self.assertEqual(self.client.get(self.audio, headers=self.other).content, b"independent")
        self.assertEqual(self.client.get(self.audio, headers=self.primary).content, self.AUDIO)

        foreign = self.client.post("/v1/speakers", headers=self.other, json={"name": "Чужой"}).json()["id"]
        self.assertEqual(self.client.get("/v1/speakers", headers=self.primary).json(), {"speakers": []})
        self.assertEqual(self.client.patch("/v1/speakers/" + foreign, headers=self.primary,
                                           json={"name": "Попытка"}).status_code, 404)
        first = self.transcript_with("Привет")
        self.assertEqual(self.client.put(self.transcript, headers=self.primary, json=first).status_code, 200)
        self.assertEqual(self.client.put(self.links + "/speaker%2F1", headers=self.primary,
                                         json={"speaker_id": foreign}).status_code, 404)

    def test_transcript_revisions_and_late_duplicate(self):
        self.assertEqual(self.upload().status_code, 201)
        self.assertEqual(self.client.get(self.transcript, headers=self.primary).status_code, 404)
        ordered = self.transcript_with("Первый", "Второй")
        reversed_input = {"transcript": list(reversed(ordered["transcript"]))}
        response = self.client.put(self.transcript, headers=self.primary, json=reversed_input)
        self.assertEqual(response.json(), {"revision": 1})
        self.assertEqual(response.headers[self.REVISION], "1")
        self.assertEqual(self.client.get(self.transcript, headers=self.primary).json(), ordered)

        second = self.transcript_with("Исправление")
        self.assertEqual(self.client.put(self.transcript, headers=self.primary, json=second).json(),
                         {"revision": 2})
        self.assertEqual(self.client.put(self.transcript, headers=self.primary, json=ordered).json(),
                         {"revision": 1})
        latest = self.client.get(self.transcript, headers=self.primary)
        self.assertEqual(latest.json(), second)
        self.assertEqual(latest.headers[self.REVISION], "2")
        self.assertEqual(self.client.get(self.transcript + "?revision=1", headers=self.primary).json(), ordered)
        revisions = self.client.get(self.transcript + "/revisions", headers=self.primary).json()["revisions"]
        self.assertEqual([(r["revision"], r["current"]) for r in revisions], [(1, False), (2, True)])

    def test_automatic_transcript_only_fills_missing_recording(self):
        self.assertEqual(self.upload().status_code, 201)
        guard = {**self.primary, "If-None-Match": "*"}
        generated = self.transcript_with("Результат движка")
        imported = self.transcript_with("Исправлено вручную")
        self.assertEqual(self.client.post(self.transcript, headers=self.primary, json=generated).json(), {"revision": 1})
        self.assertEqual(self.client.put(self.transcript, headers=guard, json=generated).json(), {"revision": 1})
        self.assertEqual(self.client.put(self.transcript, headers=self.primary, json=imported).json(), {"revision": 2})
        self.assertEqual(self.client.put(self.transcript, headers=guard, json=generated).status_code, 412)
        self.assertEqual(self.client.post(self.transcript, headers=self.primary, json=generated).status_code, 412)
        self.assertEqual(self.client.get(self.transcript, headers=self.primary).json(), imported)
        self.assertEqual(len(self.client.get(self.transcript + "/revisions",
                                            headers=self.primary).json()["revisions"]), 2)

    def test_emotion_probabilities_round_trip_and_preserve_legacy_revision(self):
        self.assertEqual(self.upload().status_code, 201)
        payload = self.transcript_with("Первая", "Вторая", "Без оценок")
        self.assertEqual(self.client.put(self.transcript, headers=self.primary, json=payload).json(), {"revision": 1})
        payload["transcript"][0]["emotion_probabilities"] = [0.8, 0.1, 0.09, 0.01]
        payload["transcript"][1]["emotion_probabilities"] = [0.00000001, 0.89999999, 0.1, 0]
        self.assertEqual(self.client.put(self.transcript, headers=self.primary, json=payload).json(), {"revision": 2})
        with TestClient(create_app(self.settings)) as restarted:
            self.assertEqual(restarted.get(self.transcript, headers=self.primary).json(), payload)
            legacy = self.transcript_with("Первая", "Вторая", "Без оценок")
            self.assertEqual(restarted.get(self.transcript + "?revision=1", headers=self.primary).json(), legacy)
            self.assertEqual(restarted.put(self.transcript, headers=self.primary, json=legacy).json(), {"revision": 1})

    def test_invalid_emotion_probabilities_are_rejected(self):
        self.assertEqual(self.upload().status_code, 201)
        payload = self.transcript_with("Привет")
        for scores in (None, [1, 0, 0], [1, 0, 0, 0, 0], [1.01, -0.01, 0, 0],
                       [0.8, 0, 0, 0], ["1", 0, 0, 0], [True, 0, 0, 0]):
            with self.subTest(scores=scores):
                payload["transcript"][0]["emotion_probabilities"] = scores
                result = self.client.put(self.transcript, headers=self.primary, json=payload)
                self.assertEqual(result.status_code, 422, result.text)

    def test_speaker_crud_and_revision_scoped_links(self):
        self.assertEqual(self.upload().status_code, 201)
        first = self.transcript_with("Реплика")
        self.assertEqual(self.client.put(self.transcript, headers=self.primary, json=first).status_code, 200)
        speaker = {"id": str(uuid4()), "name": "Аня"}
        resource = "/v1/speakers/" + speaker["id"]
        created = self.client.post("/v1/speakers", headers=self.primary, json=speaker)
        self.assertEqual(created.status_code, 201)
        self.assertEqual(created.json(), speaker)
        self.assertEqual(self.client.post("/v1/speakers", headers=self.primary, json=speaker).status_code, 200)
        self.assertEqual(self.client.patch(resource, headers=self.primary,
                                           json={"name": "Анна"}).json()["name"], "Анна")
        self.assertEqual(self.client.get("/v1/speakers", headers=self.primary).json()["speakers"],
                         [{"id": speaker["id"], "name": "Анна"}])

        link = self.links + "/speaker%2F1"
        revision_one = {**self.primary, self.REVISION: "1"}
        assignment = {"speaker_id": speaker["id"]}
        self.assertEqual(self.client.put(link, headers=revision_one, json=assignment).json(),
                         {"links": {"speaker/1": speaker["id"]}})
        self.assertEqual(self.client.put(self.links + "/unknown", headers=revision_one,
                                         json=assignment).status_code, 404)
        self.assertEqual(self.client.delete(resource, headers=self.primary).status_code, 409)

        second = self.transcript_with("Новая версия")
        self.assertEqual(self.client.put(self.transcript, headers=self.primary, json=second).status_code, 200)
        self.assertEqual(self.client.put(link, headers=revision_one, json=assignment).status_code, 409)
        self.assertEqual(self.client.get(self.links, headers=self.primary).json(), {"links": {}})
        self.assertEqual(self.client.get(self.links + "?revision=1", headers=self.primary).json(),
                         {"links": {"speaker/1": speaker["id"]}})
        revision_two = {**self.primary, self.REVISION: "2"}
        self.assertEqual(self.client.put(link, headers=revision_two, json=assignment).status_code, 200)
        self.assertEqual(self.client.put(link, headers=revision_two, json={"speaker_id": None}).json(),
                         {"links": {}})
        self.assertEqual(self.client.delete(resource, headers=self.primary).status_code, 409)
        self.assertEqual(self.client.delete(self.recording, headers=self.primary).status_code, 204)
        self.assertEqual(self.client.delete(resource, headers=self.primary).status_code, 204)

    def test_recording_deletion_survives_restart_and_blocks_retry(self):
        self.assertEqual(self.upload().status_code, 201)
        self.assertEqual(self.upload(auth=self.other, content=b"other").status_code, 201)
        self.assertEqual(self.client.delete(self.recording, headers=self.primary).status_code, 204)
        self.assertEqual(self.client.delete(self.recording, headers=self.primary).status_code, 204)
        self.assertEqual(len(list((self.data / "audio").rglob("*.audio"))), 1)
        with TestClient(create_app(self.settings)) as restarted:
            self.assertEqual(restarted.get(self.audio, headers=self.primary).status_code, 404)
            self.assertEqual(restarted.get(self.audio, headers=self.other).content, b"other")
            rejected = restarted.put(self.audio, content=self.AUDIO,
                                     headers={**self.primary, "Content-Type": self.AUDIO_TYPE})
            self.assertEqual(rejected.status_code, 410)

    def test_streaming_and_fixed_length_limits_and_validation(self):
        self.assertEqual(self.upload(content=b"x" * 65).status_code, 413)
        self.assertEqual(self.upload(content=iter((b"a" * 33, b"b" * 32))).status_code, 413)
        self.assertEqual(self.upload(content=b"").status_code, 422)
        self.assertEqual(self.client.put(self.audio, content=self.AUDIO,
                                         headers={**self.primary, "Content-Type": "text/plain"}).status_code, 415)
        self.assertEqual(self.client.get(self.recording, headers=self.primary).status_code, 404)
        self.assertEqual(list((self.data / "staging").iterdir()), [])
        self.assertEqual(self.upload(content=b"x" * 64).status_code, 201)

        broken = self.transcript_with("Содержимое не должно попасть в ошибку")
        broken["transcript"][0]["end_ms"] = 0
        rejected = self.client.put(self.transcript, headers=self.primary, json=broken)
        self.assertEqual(rejected.status_code, 422)
        self.assertEqual(rejected.json(), {"detail": "Invalid request"})


if __name__ == "__main__":
    unittest.main()
