from contextlib import closing, contextmanager
from datetime import UTC, datetime
from hashlib import sha256
import json
import os
from pathlib import Path
import sqlite3
from uuid import uuid4

from .schemas import Metadata, Transcript


class Missing(Exception):
    pass


class Conflict(Exception):
    pass


class Gone(Exception):
    pass


class PreconditionFailed(Exception):
    pass


def now() -> str:
    return datetime.now(UTC).isoformat()


def sync_directory(directory: Path) -> None:
    if os.name == "posix":
        descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


class Store:
    def __init__(self, root: Path) -> None:
        self.audio = root / "audio"
        self.staging = root / "staging"
        self.database = root / "vexto.sqlite3"
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.audio.mkdir(mode=0o700, exist_ok=True)
        self.staging.mkdir(mode=0o700, exist_ok=True)
        # A single API instance owns this data directory. No request is active at startup.
        for orphan in self.staging.glob("upload-*"):
            if orphan.is_file() or orphan.is_symlink():
                orphan.unlink(missing_ok=True)
        with closing(sqlite3.connect(self.database, timeout=15, isolation_level=None)) as db:
            db.execute("PRAGMA journal_mode=WAL")
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0, 1, 2):
                raise RuntimeError("Unsupported Vexto database schema")
            if version < 2:
                files = ("schema.sql", "retention.sql") if version == 0 else ("retention.sql",)
                schema = "\n".join(Path(__file__).with_name(name).read_text(encoding="utf-8") for name in files)
                db.executescript("BEGIN IMMEDIATE;\n" + schema + "\nPRAGMA user_version=2;\nCOMMIT;")
            for row in db.execute("SELECT workspace,id FROM recording_tombstones"):
                self.remove_audio(row[0], row[1])

    @contextmanager
    def connection(self, *, write=False):
        db = sqlite3.connect(self.database, timeout=15, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def path(self, workspace: str, recording: str) -> Path:
        key = sha256(workspace.encode("ascii")).hexdigest()
        return self.audio / key / (recording + ".audio")

    def publish_audio(self, workspace: str, recording: str, temporary: Path,
                      checksum: str, size: int, mime: str) -> bool:
        target = self.path(workspace, recording)
        with self.connection(write=True) as db:
            if self.tombstoned(db, workspace, recording):
                raise Gone("This recording was deleted")
            existing = db.execute(
                "SELECT checksum FROM recordings WHERE workspace=? AND id=?", (workspace, recording)
            ).fetchone()
            if existing:
                if existing["checksum"] != checksum:
                    raise Conflict("This recording ID already contains different audio")
                return False
            if not target.parent.exists():
                target.parent.mkdir(mode=0o700)
                sync_directory(self.audio)
            # Publication precedes the database commit. An interrupted write leaves an
            # invisible blob that a retry for the same ID can safely replace.
            os.replace(temporary, target)
            sync_directory(target.parent)
            db.execute(
                "INSERT INTO recordings (workspace,id,checksum,bytes,upload_mime,uploaded_at) VALUES (?,?,?,?,?,?)",
                (workspace, recording, checksum, size, mime, now()),
            )
        return True

    @staticmethod
    def tombstoned(db, workspace: str, recording: str) -> bool:
        return db.execute("SELECT 1 FROM recording_tombstones WHERE workspace=? AND id=?",
                          (workspace, recording)).fetchone() is not None

    def remove_audio(self, workspace: str, recording: str) -> None:
        path = self.path(workspace, recording)
        try:
            path.unlink()
        except FileNotFoundError:
            return
        sync_directory(path.parent)

    def delete_recording(self, workspace: str, recording: str) -> None:
        with self.connection(write=True) as db:
            if not self.tombstoned(db, workspace, recording):
                self.require_recording(db, workspace, recording)
                db.execute("DELETE FROM recordings WHERE workspace=? AND id=?", (workspace, recording))
                db.execute("INSERT INTO recording_tombstones VALUES (?,?)", (workspace, recording))
        # Delete only after commit. A surviving tombstone both enables crash recovery
        # and prevents a delayed upload from recreating the deleted recording.
        self.remove_audio(workspace, recording)

    def open_audio(self, workspace: str, recording: str):
        with self.connection() as db:
            row = self.require_recording(db, workspace, recording)
            return self.path(workspace, recording).open("rb"), self.recording_dict(row)

    @staticmethod
    def require_recording(db, workspace: str, recording: str):
        row = db.execute(
            "SELECT * FROM recordings WHERE workspace=? AND id=?", (workspace, recording)
        ).fetchone()
        if row is None:
            raise Missing("Recording not found")
        return row

    @staticmethod
    def recording_dict(row) -> dict:
        metadata = json.loads(row["metadata"]) if row["metadata"] else {}
        return {
            "id": row["id"], "title": metadata.get("title"), "duration_ms": metadata.get("duration_ms"),
            "recorded_at": metadata.get("recorded_at"), "location": metadata.get("location"),
            "filename": metadata.get("filename"), "mime_type": metadata.get("mime_type", row["upload_mime"]),
            "bytes": row["bytes"], "has_transcript": row["current_revision"] is not None,
        }

    def recording(self, workspace: str, recording: str) -> dict:
        with self.connection() as db:
            return self.recording_dict(self.require_recording(db, workspace, recording))

    def recordings(self, workspace: str, limit: int, offset: int) -> list[dict]:
        with self.connection() as db:
            return [self.recording_dict(row) for row in db.execute(
                "SELECT * FROM recordings WHERE workspace=? ORDER BY uploaded_at DESC,id DESC LIMIT ? OFFSET ?",
                (workspace, limit, offset),
            )]

    def set_metadata(self, workspace: str, recording: str, metadata: Metadata) -> dict:
        with self.connection(write=True) as db:
            self.require_recording(db, workspace, recording)
            db.execute(
                "UPDATE recordings SET metadata=? WHERE workspace=? AND id=?",
                (metadata.model_dump_json(), workspace, recording),
            )
            return self.recording_dict(self.require_recording(db, workspace, recording))

    def set_transcript(self, workspace: str, recording: str, transcript: Transcript, *, create_only=False) -> int:
        lines = sorted(transcript.transcript, key=lambda line: line.start_ms)
        body = Transcript(transcript=lines).model_dump_json()
        checksum = sha256(body.encode("utf-8")).hexdigest()
        with self.connection(write=True) as db:
            current = self.require_recording(db, workspace, recording)["current_revision"]
            prior = db.execute(
                "SELECT revision FROM transcripts WHERE workspace=? AND recording_id=? AND checksum=?",
                (workspace, recording, checksum),
            ).fetchone()
            if create_only and current is not None and (prior is None or prior["revision"] != current):
                raise PreconditionFailed("A transcript is already available")
            if prior:
                return prior["revision"]
            revision = db.execute(
                "SELECT COALESCE(MAX(revision),0)+1 FROM transcripts WHERE workspace=? AND recording_id=?",
                (workspace, recording),
            ).fetchone()[0]
            db.execute(
                "INSERT INTO transcripts VALUES (?,?,?,?,?,?)",
                (workspace, recording, revision, checksum, body, now()),
            )
            db.execute(
                "UPDATE recordings SET current_revision=? WHERE workspace=? AND id=?",
                (revision, workspace, recording),
            )
        return revision

    def transcript(self, workspace: str, recording: str, revision: int | None = None) -> tuple[str, int]:
        with self.connection() as db:
            row = self.require_recording(db, workspace, recording)
            if revision is None:
                revision = row["current_revision"]
            result = db.execute(
                "SELECT body FROM transcripts WHERE workspace=? AND recording_id=? AND revision=?",
                (workspace, recording, revision),
            ).fetchone()
            if result is None:
                raise Missing("Transcript not found")
            return result["body"], revision

    def history(self, workspace: str, recording: str) -> list[dict]:
        with self.connection() as db:
            row = self.require_recording(db, workspace, recording)
            return [dict(result, current=result["revision"] == row["current_revision"]) for result in db.execute(
                "SELECT revision,created_at FROM transcripts WHERE workspace=? AND recording_id=? ORDER BY revision",
                (workspace, recording),
            )]

    def speakers(self, workspace: str, limit: int, offset: int) -> list[dict]:
        with self.connection() as db:
            return [dict(row) for row in db.execute(
                "SELECT id,name FROM speakers WHERE workspace=? ORDER BY name COLLATE NOCASE,id LIMIT ? OFFSET ?",
                (workspace, limit, offset),
            )]

    def create_speaker(self, workspace: str, name: str, speaker_id: str | None) -> tuple[dict, bool]:
        speaker_id = speaker_id or str(uuid4())
        with self.connection(write=True) as db:
            row = db.execute(
                "SELECT name FROM speakers WHERE workspace=? AND id=?", (workspace, speaker_id)
            ).fetchone()
            if row:
                if row["name"] != name:
                    raise Conflict("This speaker ID is already in use")
                return {"id": speaker_id, "name": name}, False
            db.execute("INSERT INTO speakers VALUES (?,?,?)", (workspace, speaker_id, name))
            return {"id": speaker_id, "name": name}, True

    def rename_speaker(self, workspace: str, speaker_id: str, name: str) -> dict:
        with self.connection(write=True) as db:
            if not db.execute(
                "UPDATE speakers SET name=? WHERE workspace=? AND id=?", (name, workspace, speaker_id)
            ).rowcount:
                raise Missing("Speaker not found")
            return {"id": speaker_id, "name": name}

    def delete_speaker(self, workspace: str, speaker_id: str) -> None:
        with self.connection(write=True) as db:
            try:
                deleted = db.execute("DELETE FROM speakers WHERE workspace=? AND id=?", (workspace, speaker_id))
            except sqlite3.IntegrityError as error:
                raise Conflict("Speaker is linked to a transcript revision") from error
            if not deleted.rowcount:
                raise Missing("Speaker not found")

    @staticmethod
    def read_links(db, workspace: str, recording: str, revision: int | None) -> dict[str, str]:
        return {row["local_id"]: row["speaker_id"] for row in db.execute(
            "SELECT local_id,speaker_id FROM speaker_links WHERE workspace=? AND recording_id=? AND revision=?",
            (workspace, recording, revision),
        )}

    def links(self, workspace: str, recording: str, revision: int | None) -> tuple[dict[str, str], int | None]:
        with self.connection() as db:
            row = self.require_recording(db, workspace, recording)
            if revision is None:
                revision = row["current_revision"]
            elif db.execute(
                "SELECT 1 FROM transcripts WHERE workspace=? AND recording_id=? AND revision=?",
                (workspace, recording, revision),
            ).fetchone() is None:
                raise Missing("Transcript not found")
            return self.read_links(db, workspace, recording, revision), revision

    def set_link(self, workspace: str, recording: str, local_id: str, speaker_id: str | None,
                 expected_revision: int | None) -> dict[str, str]:
        with self.connection(write=True) as db:
            row = self.require_recording(db, workspace, recording)
            revision = row["current_revision"]
            if expected_revision is not None and expected_revision != revision:
                raise Conflict("The transcript has changed")
            key = (workspace, recording, revision)
            transcript = db.execute(
                "SELECT body FROM transcripts WHERE workspace=? AND recording_id=? AND revision=?", key
            ).fetchone()
            if transcript is None or not any(
                line["speaker_id"] == local_id for line in json.loads(transcript["body"])["transcript"]
            ):
                raise Missing("Local speaker not found in the current transcript")
            if speaker_id is None:
                db.execute(
                    "DELETE FROM speaker_links WHERE workspace=? AND recording_id=? AND revision=? AND local_id=?",
                    (*key, local_id),
                )
            else:
                if db.execute("SELECT 1 FROM speakers WHERE workspace=? AND id=?", (workspace, speaker_id)).fetchone() is None:
                    raise Missing("Speaker not found")
                db.execute(
                    "INSERT INTO speaker_links VALUES (?,?,?,?,?) "
                    "ON CONFLICT(workspace,recording_id,revision,local_id) DO UPDATE SET speaker_id=excluded.speaker_id",
                    (*key, local_id, speaker_id),
                )
            return self.read_links(db, workspace, recording, revision)
