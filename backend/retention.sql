CREATE TABLE IF NOT EXISTS recording_tombstones (
    workspace TEXT NOT NULL,
    id TEXT NOT NULL,
    PRIMARY KEY (workspace, id)
);
