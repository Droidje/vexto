package com.vexto.reflect;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stable conversation numbers linked to the private recording filenames. */
final class ReflectConversationStore extends SQLiteOpenHelper {
    ReflectConversationStore(Context context) {
        super(context, "conversations.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE conversations ("
                + "number INTEGER PRIMARY KEY AUTOINCREMENT, recording TEXT NOT NULL UNIQUE)");
    }

    @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unknown conversation database migration");
    }

    Map<String, Long> assign(List<ReflectCatalog.Recording> recordings) {
        SQLiteDatabase database = getWritableDatabase();
        Map<String, Long> numbers = new HashMap<>();
        database.beginTransaction();
        try {
            try (var rows = database.query("conversations", new String[]{"recording", "number"},
                    null, null, null, null, null)) {
                while (rows.moveToNext()) numbers.put(rows.getString(0), rows.getLong(1));
            }
            // The catalog is newest-first. Number existing recordings oldest-first on migration.
            for (int index = recordings.size() - 1; index >= 0; index--) {
                String recording = recordings.get(index).file.getName();
                if (!numbers.containsKey(recording)) numbers.put(recording, insert(database, recording));
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return numbers;
    }

    String title(File recording) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            String name = recording.getName();
            long number;
            try (var rows = database.query("conversations", new String[]{"number"}, "recording = ?",
                    new String[]{name}, null, null, null)) {
                number = rows.moveToFirst() ? rows.getLong(0) : insert(database, name);
            }
            database.setTransactionSuccessful();
            return title(number);
        } finally {
            database.endTransaction();
        }
    }

    static String title(Long number) {
        return number == null ? "Разговор" : "Разговор №" + number;
    }

    private static long insert(SQLiteDatabase database, String recording) {
        ContentValues row = new ContentValues(1);
        row.put("recording", recording);
        return database.insertOrThrow("conversations", null, row);
    }
}
