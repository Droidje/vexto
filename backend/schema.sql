CREATE TABLE IF NOT EXISTS recordings (
    workspace TEXT NOT NULL,
    id TEXT NOT NULL,
    checksum TEXT NOT NULL,
    bytes INTEGER NOT NULL CHECK (bytes > 0),
    upload_mime TEXT NOT NULL,
    uploaded_at TEXT NOT NULL,
    metadata TEXT,
    current_revision INTEGER,
    PRIMARY KEY (workspace, id)
);
CREATE INDEX IF NOT EXISTS recordings_by_upload ON recordings (workspace, uploaded_at DESC, id DESC);
CREATE TABLE IF NOT EXISTS transcripts (
    workspace TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    checksum TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (workspace, recording_id, revision),
    UNIQUE (workspace, recording_id, checksum),
    FOREIGN KEY (workspace, recording_id) REFERENCES recordings (workspace, id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS speakers (
    workspace TEXT NOT NULL,
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (workspace, id)
);
CREATE TABLE IF NOT EXISTS speaker_links (
    workspace TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    local_id TEXT NOT NULL,
    speaker_id TEXT NOT NULL,
    PRIMARY KEY (workspace, recording_id, revision, local_id),
    FOREIGN KEY (workspace, recording_id, revision)
        REFERENCES transcripts (workspace, recording_id, revision) ON DELETE CASCADE,
    FOREIGN KEY (workspace, speaker_id) REFERENCES speakers (workspace, id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS linked_speakers ON speaker_links (workspace, speaker_id);
