package com.vexto.reflect;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persist the distinction between a user's decision and a retryable local placeholder. */
final class SpeakerLinkPolicy {
    static final String MANUAL = "manual";
    static final String PENDING = "pending";
    static final String AUTOMATIC = "automatic";

    private SpeakerLinkPolicy() {}

    static void upgrade(SQLiteDatabase db) {
        // Old databases did not record provenance. Preserve every old decision rather than guess.
        db.execSQL("ALTER TABLE links ADD COLUMN origin TEXT NOT NULL DEFAULT 'manual' "
                + "CHECK(origin IN ('manual','pending','automatic'))");
    }

    static Map<String, String> retryable(SQLiteDatabase db, String recording, String revision) {
        Map<String, String> result = new LinkedHashMap<>();
        try (Cursor rows = db.rawQuery("SELECT l.local_id,l.profile_id FROM links l "
                + "JOIN profiles p ON p.id=l.profile_id "
                + "WHERE l.recording=? AND l.revision=? AND l.origin='pending' "
                + "AND p.automatic=1 AND p.is_self=0 "
                // Once another conversation uses this identity, changing only one link would split it.
                + "AND NOT EXISTS(SELECT 1 FROM links other WHERE other.profile_id=l.profile_id "
                + "AND (other.recording<>l.recording OR other.origin='manual'))",
                new String[]{recording, revision})) {
            while (rows.moveToNext()) result.put(rows.getString(0), rows.getString(1));
        }
        return result;
    }

    static void confirmProfile(SQLiteDatabase db, String profile) {
        db.execSQL("UPDATE links SET origin='manual' WHERE profile_id=?", new Object[]{profile});
    }
}
