package com.vexto.reflect;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.Closeable;
import java.io.File;

/** Durable local FIFO. A single coordinator owns draining; all checkpoints survive process death. */
final class ReflectEmotionQueue implements Closeable {
    private static final long MAX_RETRY_MS = 6 * 60 * 60 * 1000L;
    private final Database helper;

    static final class Job {
        final long id;
        final String recordingName;
        final String revision;
        final long sourceLength;
        final long sourceModified;
        final int nextIndex;
        final int attempts;

        Job(Cursor cursor) {
            id = cursor.getLong(0);
            recordingName = cursor.getString(1);
            revision = cursor.getString(2);
            sourceLength = cursor.getLong(3);
            sourceModified = cursor.getLong(4);
            nextIndex = cursor.getInt(5);
            attempts = cursor.getInt(6);
        }
    }

    ReflectEmotionQueue(Context context) { this(context, "reflect-emotions.db"); }
    ReflectEmotionQueue(Context context, String databaseName) {
        helper = new Database(context.getApplicationContext(), databaseName);
    }

    /** Same revision includes completed tombstones; a changed source/base transcript joins the tail. */
    synchronized void enqueue(File recording, ReflectTranscriptStore.Snapshot snapshot) {
        if (snapshot == null) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor existing = db.query("jobs", null, "recording_name=?",
                    new String[]{recording.getName()}, null, null, null)) {
                if (existing.moveToFirst()
                        && snapshot.revision.equals(existing.getString(existing.getColumnIndexOrThrow("revision")))
                        && snapshot.sourceLength == existing.getLong(existing.getColumnIndexOrThrow("source_length"))
                        && snapshot.sourceModified == existing.getLong(existing.getColumnIndexOrThrow("source_modified"))) {
                    db.setTransactionSuccessful();
                    return;
                }
            }
            db.delete("jobs", "recording_name=?", new String[]{recording.getName()});
            ContentValues values = new ContentValues();
            values.put("recording_name", recording.getName());
            values.put("revision", snapshot.revision);
            values.put("source_length", snapshot.sourceLength);
            values.put("source_modified", snapshot.sourceModified);
            db.insertOrThrow("jobs", null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized Job peek() {
        try (Cursor cursor = helper.getReadableDatabase().query("jobs", new String[]{"id",
                "recording_name", "revision", "source_length", "source_modified", "next_index", "attempts"},
                "done=0 AND ready_at<=?", new String[]{Long.toString(System.currentTimeMillis())},
                null, null, "id ASC", "1")) {
            return cursor.moveToFirst() ? new Job(cursor) : null;
        }
    }

    /** Invoke only after the sidecar merge committed (or an unsupported/already-scored line was skipped). */
    synchronized void advance(Job job, int nextIndex) {
        if (nextIndex <= job.nextIndex) throw new IllegalArgumentException("Cursor must advance");
        ContentValues values = new ContentValues();
        values.put("next_index", nextIndex);
        values.put("attempts", 0);
        values.put("ready_at", 0);
        helper.getWritableDatabase().update("jobs", values, "id=? AND done=0 AND next_index<?",
                new String[]{Long.toString(job.id), Integer.toString(nextIndex)});
    }

    synchronized void finish(Job job) {
        ContentValues values = new ContentValues();
        values.put("done", 1);
        update(job, values);
    }

    /** Foreground work gets priority without consuming retry attempts or FIFO position. */
    synchronized void deferYield(Job job) {
        ContentValues values = new ContentValues();
        values.put("ready_at", System.currentTimeMillis() + 1000);
        update(job, values);
    }

    /** A failing recording sleeps, allowing later recordings to proceed. */
    synchronized void fail(Job job) {
        int attempts = Math.min(30, job.attempts + 1);
        long delay = Math.min(MAX_RETRY_MS, 30_000L << Math.min(10, attempts - 1));
        ContentValues values = new ContentValues();
        values.put("attempts", attempts);
        values.put("ready_at", System.currentTimeMillis() + delay);
        update(job, values);
    }

    synchronized boolean hasPending() { return nextDelayMs() >= 0; }

    /** -1 means no pending work; 0 means runnable now. Wall time remains meaningful after reboot. */
    synchronized long nextDelayMs() {
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT MIN(ready_at) FROM jobs WHERE done=0", null)) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) return -1;
            return Math.max(0, cursor.getLong(0) - System.currentTimeMillis());
        }
    }

    synchronized void remove(Job job) {
        helper.getWritableDatabase().delete("jobs", "id=?", new String[]{Long.toString(job.id)});
    }

    /** Lazy pruning also removes completed tombstones for deleted recordings. */
    synchronized void pruneMissing(File recordingsDirectory) {
        SQLiteDatabase db = helper.getWritableDatabase();
        try (Cursor cursor = db.query("jobs", new String[]{"id", "recording_name"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                if (!new File(recordingsDirectory, cursor.getString(1)).isFile()) {
                    db.delete("jobs", "id=?", new String[]{Long.toString(cursor.getLong(0))});
                }
            }
        }
    }

    private void update(Job job, ContentValues values) {
        helper.getWritableDatabase().update("jobs", values, "id=? AND done=0",
                new String[]{Long.toString(job.id)});
    }

    @Override public synchronized void close() { helper.close(); }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context, String name) { super(context, name, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "recording_name TEXT NOT NULL UNIQUE, revision TEXT NOT NULL, "
                    + "source_length INTEGER NOT NULL, source_modified INTEGER NOT NULL, "
                    + "next_index INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, "
                    + "ready_at INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported emotion queue schema upgrade");
        }
    }
}
