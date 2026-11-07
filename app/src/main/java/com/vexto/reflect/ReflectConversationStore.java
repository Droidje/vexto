package com.vexto.reflect;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stable conversation identity and title lifecycle, independent of the recording title. */
final class ReflectConversationStore extends SQLiteOpenHelper {
    private static final int PENDING = 0, AUTOMATIC = 1, MANUAL = 2, DELETED = 3;

    record Entry(long number, String customTitle, int state) {
        String title() { return customTitle == null ? numberedTitle(number) : customTitle; }
        boolean needsTitle() { return state == PENDING; }
        boolean deleted() { return state == DELETED; }
    }

    ReflectConversationStore(Context context) {
        super(context, "conversations.db", null, 2);
    }

    @Override public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE conversations (number INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "recording TEXT NOT NULL UNIQUE, title TEXT, title_state INTEGER NOT NULL DEFAULT 0)");
    }

    @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) throw new IllegalStateException("Unknown conversation migration");
        database.execSQL("ALTER TABLE conversations ADD COLUMN title TEXT");
        database.execSQL("ALTER TABLE conversations ADD COLUMN title_state INTEGER NOT NULL DEFAULT 0");
    }

    Map<String, Entry> assign(List<ReflectCatalog.Recording> recordings) {
        SQLiteDatabase database = getWritableDatabase();
        Map<String, Entry> entries = new HashMap<>();
        database.beginTransaction();
        try {
            try (var rows = database.query("conversations",
                    new String[]{"recording", "number", "title", "title_state"}, null, null, null, null, null)) {
                while (rows.moveToNext()) {
                    entries.put(rows.getString(0), new Entry(rows.getLong(1), rows.getString(2), rows.getInt(3)));
                }
            }
            // Number pre-existing recordings in their original order.
            for (int index = recordings.size() - 1; index >= 0; index--) {
                String recording = recordings.get(index).file.getName();
                if (!entries.containsKey(recording)) entries.put(recording, insert(database, recording));
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return entries;
    }

    String title(File recording) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Entry entry = find(database, recording);
            if (entry == null) entry = insert(database, recording.getName());
            database.setTransactionSuccessful();
            return entry.title();
        } finally {
            database.endTransaction();
        }
    }

    boolean needsTitle(File recording) {
        Entry entry = find(getReadableDatabase(), recording);
        return entry != null && entry.needsTitle();
    }

    boolean isDeleted(File recording) {
        Entry entry = find(getReadableDatabase(), recording);
        return entry != null && entry.deleted();
    }

    boolean rename(File recording, String title) {
        return update(recording, title, MANUAL, "title_state != ?", DELETED);
    }

    /** A null title records a completed attempt that produced no usable name. */
    boolean completeTitle(File recording, String title) {
        return update(recording, title, AUTOMATIC, "title_state = ?", PENDING);
    }

    /** Explicit navigation from a retained recording can restore its archived conversation. */
    boolean restore(File recording) {
        ContentValues values = new ContentValues(1);
        values.put("title_state", AUTOMATIC);
        return getWritableDatabase().update("conversations", values, "recording = ? AND title_state = ?",
                new String[]{recording.getName(), Integer.toString(DELETED)}) == 1;
    }

    boolean delete(File recording) {
        return update(recording, null, DELETED, "title_state != ?", DELETED);
    }

    static String numberedTitle(long number) { return "Разговор №" + number; }

    private boolean update(File recording, String title, int state, String filter, int previousState) {
        ContentValues values = new ContentValues(2);
        values.put("title", title);
        values.put("title_state", state);
        return getWritableDatabase().update("conversations", values, "recording = ? AND " + filter,
                new String[]{recording.getName(), Integer.toString(previousState)}) == 1;
    }

    private static Entry find(SQLiteDatabase database, File recording) {
        try (var rows = database.query("conversations", new String[]{"number", "title", "title_state"},
                "recording = ?", new String[]{recording.getName()}, null, null, null)) {
            return rows.moveToFirst() ? new Entry(rows.getLong(0), rows.getString(1), rows.getInt(2)) : null;
        }
    }

    private static Entry insert(SQLiteDatabase database, String recording) {
        ContentValues row = new ContentValues(1);
        row.put("recording", recording);
        return new Entry(database.insertOrThrow("conversations", null, row), null, PENDING);
    }
}
