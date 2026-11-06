package com.vexto.reflect;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.vexto.engine.model.Speaker;
import com.vexto.engine.model.VoiceEmbedding;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private on-device profiles, confirmed links and model-versioned recording signatures. */
final class SpeakerProfiles extends SQLiteOpenHelper {
    record Profile(String id, String name) {}
    record Suggestion(Profile profile, double score) {}
    private final File recordings;

    SpeakerProfiles(Context context) {
        super(context.getApplicationContext(), "speaker-profiles.db", null, 1);
        recordings = new File(context.getFilesDir(), "recordings");
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        db.execSQL("CREATE TABLE links (recording TEXT NOT NULL, revision TEXT NOT NULL, local_id TEXT NOT NULL, "
                + "profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE, "
                + "PRIMARY KEY(recording, revision, local_id))");
        db.execSQL("CREATE INDEX links_profile ON links(profile_id)");
        db.execSQL("CREATE TABLE embeddings (recording TEXT NOT NULL, revision TEXT NOT NULL, local_id TEXT NOT NULL, "
                + "model_id TEXT NOT NULL, dimension INTEGER NOT NULL CHECK(dimension BETWEEN 1 AND 4096), "
                + "vector BLOB NOT NULL, speech_ms INTEGER NOT NULL CHECK(speech_ms > 0), "
                + "windows INTEGER NOT NULL CHECK(windows > 0), created_at INTEGER NOT NULL, "
                + "PRIMARY KEY(recording, revision, local_id, model_id))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported profile schema upgrade");
    }

    List<Profile> list() {
        List<Profile> profiles = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().query("profiles", new String[]{"id", "name"},
                null, null, null, null, "name COLLATE NOCASE, id")) {
            while (rows.moveToNext()) profiles.add(new Profile(rows.getString(0), rows.getString(1)));
        }
        return Collections.unmodifiableList(profiles);
    }

    Profile create(String name) {
        Profile profile = new Profile(UUID.randomUUID().toString(), checkedName(name));
        ContentValues values = new ContentValues(); values.put("id", profile.id); values.put("name", profile.name);
        getWritableDatabase().insertOrThrow("profiles", null, values);
        return profile;
    }

    void rename(String id, String name) {
        ContentValues values = new ContentValues(); values.put("name", checkedName(name));
        if (getWritableDatabase().update("profiles", values, "id=?", new String[]{id}) != 1)
            throw new IllegalArgumentException("Unknown profile");
    }

    void delete(String id) { getWritableDatabase().delete("profiles", "id=?", new String[]{id}); }

    Map<String, String> links(File recording, ReflectTranscript transcript) throws IOException {
        Map<String, String> links = new HashMap<>();
        try (Cursor rows = getReadableDatabase().query("links", new String[]{"local_id", "profile_id"},
                "recording=? AND revision=?", new String[]{recordingKey(recording), revision(transcript)},
                null, null, null)) {
            while (rows.moveToNext()) links.put(rows.getString(0), rows.getString(1));
        }
        return links;
    }

    Map<String, String> names(File recording, ReflectTranscript transcript) throws IOException {
        Map<String, String> allNames = new HashMap<>();
        for (Profile profile : list()) allNames.put(profile.id, profile.name);
        Map<String, String> names = new HashMap<>();
        links(recording, transcript).forEach((local, profile) -> {
            if (allNames.containsKey(profile)) names.put(local, allNames.get(profile));
        });
        return names;
    }

    void link(File recording, ReflectTranscript transcript, String localId, String profileId) throws IOException {
        if (transcript.lines.stream().noneMatch(line -> line.speakerId.equals(localId)))
            throw new IllegalArgumentException("Speaker is not in this transcript");
        String key = recordingKey(recording), revision = revision(transcript);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("links", "recording=? AND revision=? AND local_id=?", new String[]{key, revision, localId});
            if (profileId != null) {
                ContentValues values = new ContentValues(); values.put("recording", key); values.put("revision", revision);
                values.put("local_id", localId); values.put("profile_id", profileId);
                db.insertOrThrow("links", null, values);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Save only final signatures, not every audio window. Link/enrollment remains user-confirmed. */
    void saveEmbeddings(File recording, ReflectTranscript transcript, List<Speaker> speakers) throws IOException {
        String key = recordingKey(recording), revision = revision(transcript);
        List<ContentValues> prepared = new ArrayList<>();
        for (Speaker speaker : speakers) {
            VoiceEmbedding voice = speaker.getVoice();
            if (voice == null || voice.getSpeechMs() < 2000
                    || transcript.lines.stream().noneMatch(line -> line.speakerId.equals(speaker.getId()))) continue;
            float[] vector = new float[voice.getValues().size()];
            for (int i = 0; i < vector.length; i++) vector[i] = voice.getValues().get(i);
            ContentValues values = new ContentValues(); values.put("recording", key); values.put("revision", revision);
            values.put("local_id", speaker.getId()); values.put("model_id", voice.getModelId());
            values.put("dimension", vector.length); values.put("vector", VoiceVectors.encode(vector));
            values.put("speech_ms", voice.getSpeechMs()); values.put("windows", voice.getWindowCount());
            values.put("created_at", System.currentTimeMillis());
            prepared.add(values);
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (ContentValues values : prepared) db.replaceOrThrow("embeddings", null, values);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Same model only, bounded to 20 confirmed samples/profile; no automatic assignment. */
    List<Suggestion> suggestions(File recording, ReflectTranscript transcript, String localId) throws IOException {
        String key = recordingKey(recording), revision = revision(transcript);
        List<Suggestion> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor query = db.query("embeddings", new String[]{"model_id", "dimension", "vector"},
                "recording=? AND revision=? AND local_id=?", new String[]{key, revision, localId}, null, null, "created_at DESC, rowid DESC", "1")) {
            if (!query.moveToFirst()) return result;
            String model = query.getString(0); int dimension = query.getInt(1);
            float[] target = VoiceVectors.decode(query.getBlob(2), dimension);
            for (Profile profile : list()) {
                double[] centroid = new double[dimension]; int count = 0;
                try (Cursor samples = db.rawQuery("SELECT e.vector,e.speech_ms FROM embeddings e JOIN links l "
                        + "ON e.recording=l.recording AND e.revision=l.revision AND e.local_id=l.local_id "
                        + "WHERE l.profile_id=? AND e.model_id=? AND e.dimension=? AND e.recording<>? "
                        + "ORDER BY e.rowid DESC LIMIT 20", new String[]{profile.id, model, String.valueOf(dimension), key})) {
                    while (samples.moveToNext()) {
                        float[] vector = VoiceVectors.decode(samples.getBlob(0), dimension);
                        double weight = Math.min(30000, samples.getLong(1));
                        for (int i = 0; i < dimension; i++) centroid[i] += vector[i] * weight;
                        count++;
                    }
                }
                if (count > 0) {
                    float[] vector = new float[dimension];
                    for (int i = 0; i < dimension; i++) vector[i] = (float) centroid[i];
                    try { result.add(new Suggestion(profile, VoiceVectors.cosine(target, VoiceVectors.normalize(vector)))); }
                    catch (IllegalArgumentException opposingSamples) { /* No usable centroid. */ }
                }
            }
        }
        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
    }

    static String revision(ReflectTranscript transcript) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(transcript.encode());
            StringBuilder id = new StringBuilder(); for (byte b : hash) id.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private String recordingKey(File file) throws IOException {
        if (file == null || !file.isFile() || !recordings.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()))
            throw new IOException("Recording is outside local storage");
        return file.getName();
    }

    private static String checkedName(String name) { return ReflectTranscript.checkedText(name, 120).trim(); }
}
