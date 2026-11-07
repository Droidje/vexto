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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private on-device global speaker profiles, revision-scoped links and voice signatures. */
final class SpeakerProfiles extends SQLiteOpenHelper {
    record Profile(String id, String name, boolean self, boolean automatic) {}
    record Suggestion(Profile profile, double score) {}
    private static final String[] PROFILE_COLUMNS = {"id", "name", "is_self", "automatic"};
    private final File recordings;

    SpeakerProfiles(Context context) {
        super(context.getApplicationContext(), "speaker-profiles.db", null, 3);
        recordings = new File(context.getFilesDir(), "recordings");
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, "
                + "is_self INTEGER NOT NULL DEFAULT 0 CHECK(is_self IN (0,1)), "
                + "automatic INTEGER NOT NULL DEFAULT 0 CHECK(automatic IN (0,1)))");
        db.execSQL("CREATE TABLE links (recording TEXT NOT NULL, revision TEXT NOT NULL, local_id TEXT NOT NULL, "
                + "profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE, "
                + "origin TEXT NOT NULL DEFAULT 'manual' CHECK(origin IN ('manual','pending','automatic')), "
                + "PRIMARY KEY(recording, revision, local_id))");
        db.execSQL("CREATE INDEX links_profile ON links(profile_id)");
        db.execSQL("CREATE TABLE embeddings (recording TEXT NOT NULL, revision TEXT NOT NULL, local_id TEXT NOT NULL, "
                + "model_id TEXT NOT NULL, dimension INTEGER NOT NULL CHECK(dimension BETWEEN 1 AND 4096), "
                + "vector BLOB NOT NULL, speech_ms INTEGER NOT NULL CHECK(speech_ms > 0), "
                + "windows INTEGER NOT NULL CHECK(windows > 0), created_at INTEGER NOT NULL, "
                + "PRIMARY KEY(recording, revision, local_id, model_id))");
        createDirectorySchema(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion > 2 || newVersion != 3)
            throw new IllegalStateException("Unsupported profile schema upgrade");
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN is_self INTEGER NOT NULL DEFAULT 0 CHECK(is_self IN (0,1))");
            db.execSQL("ALTER TABLE profiles ADD COLUMN automatic INTEGER NOT NULL DEFAULT 0 CHECK(automatic IN (0,1))");
            createDirectorySchema(db);
        }
        SpeakerLinkPolicy.upgrade(db);
    }

    private static void createDirectorySchema(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX one_self_profile ON profiles(is_self) WHERE is_self=1");
        db.execSQL("CREATE TABLE active_transcripts (recording TEXT PRIMARY KEY, revision TEXT NOT NULL)");
    }

    List<Profile> list() {
        return list(false);
    }

    List<Profile> visibleProfiles() {
        return list(true);
    }

    private List<Profile> list(boolean activeOnly) {
        List<Profile> profiles = new ArrayList<>();
        String selection = activeOnly ? "automatic=0 OR is_self=1 OR EXISTS(SELECT 1 FROM links l "
                + "JOIN active_transcripts a ON a.recording=l.recording AND a.revision=l.revision "
                + "WHERE l.profile_id=profiles.id)" : null;
        try (Cursor rows = getReadableDatabase().query("profiles", PROFILE_COLUMNS,
                selection, null, null, null, "is_self DESC, name COLLATE NOCASE, id")) {
            while (rows.moveToNext()) profiles.add(readProfile(rows));
        }
        return Collections.unmodifiableList(profiles);
    }

    Profile create(String name) {
        return insertProfile(getWritableDatabase(), checkedName(name), false);
    }

    private static Profile insertProfile(SQLiteDatabase db, String name, boolean automatic) {
        Profile profile = new Profile(UUID.randomUUID().toString(), name, false, automatic);
        ContentValues values = new ContentValues(); values.put("id", profile.id); values.put("name", profile.name);
        values.put("automatic", automatic ? 1 : 0);
        db.insertOrThrow("profiles", null, values);
        return profile;
    }

    private static Profile readProfile(Cursor row) {
        return new Profile(row.getString(0), row.getString(1), row.getInt(2) != 0, row.getInt(3) != 0);
    }

    Profile get(String id) {
        return get(getReadableDatabase(), id);
    }

    private static Profile get(SQLiteDatabase db, String id) {
        try (Cursor row = db.query("profiles", PROFILE_COLUMNS, "id=?", new String[]{id}, null, null, null)) {
            if (!row.moveToFirst()) throw new IllegalArgumentException("Unknown profile");
            return readProfile(row);
        }
    }

    void rename(String id, String name) {
        ContentValues values = new ContentValues(); values.put("name", checkedName(name));
        values.put("automatic", 0);
        if (getWritableDatabase().update("profiles", values, "id=?", new String[]{id}) != 1)
            throw new IllegalArgumentException("Unknown profile");
    }

    void delete(String id) { getWritableDatabase().delete("profiles", "id=?", new String[]{id}); }

    void markSelf(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            get(db, id);
            setSelf(db, id);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void setSelf(SQLiteDatabase db, String id) {
        db.execSQL("UPDATE profiles SET is_self=0 WHERE is_self=1 AND id<>?", new Object[]{id});
        db.execSQL("UPDATE profiles SET is_self=1 WHERE id=?", new Object[]{id});
    }

    void merge(String source, String target) {
        if (source.equals(target)) throw new IllegalArgumentException("Cannot merge a profile into itself");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Profile from = get(db, source);
            get(db, target);
            db.execSQL("UPDATE links SET profile_id=? WHERE profile_id=?", new Object[]{target, source});
            SpeakerLinkPolicy.confirmProfile(db, target);
            db.delete("profiles", "id=?", new String[]{source});
            if (from.self) setSelf(db, target);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    Map<String, String> links(File recording, ReflectTranscript transcript) throws IOException {
        String key = recordingKey(recording), rev = revision(transcript);
        SQLiteDatabase db = getReadableDatabase();
        Map<String, String> result = readLinks(db, key, rev);
        if (hasEmotions(transcript)) {
            readLinks(db, key, hash(transcript.encode())).forEach(result::putIfAbsent);
        }
        return result;
    }

    private static Map<String, String> readLinks(SQLiteDatabase db, String recording, String revision) {
        Map<String, String> links = new HashMap<>();
        try (Cursor rows = db.query("links", new String[]{"local_id", "profile_id"},
                "recording=? AND revision=?", new String[]{recording, revision},
                null, null, null)) {
            while (rows.moveToNext()) links.put(rows.getString(0), rows.getString(1));
        }
        return links;
    }

    Map<String, String> names(File recording, ReflectTranscript transcript) throws IOException {
        return names(recording, transcript, true);
    }

    Map<String, String> exportNames(File recording, ReflectTranscript transcript) throws IOException {
        return names(recording, transcript, false);
    }

    private Map<String, String> names(File recording, ReflectTranscript transcript, boolean markSelf) throws IOException {
        Map<String, String> allNames = new HashMap<>();
        for (Profile profile : list()) {
            allNames.put(profile.id, markSelf && profile.self ? profile.name + " · Я" : profile.name);
        }
        Map<String, String> names = importedNames(transcript);
        links(recording, transcript).forEach((local, profile) -> {
            if (allNames.containsKey(profile)) names.put(local, allNames.get(profile));
        });
        return names;
    }

    private static Map<String, String> importedNames(ReflectTranscript transcript) {
        Map<String, String> names = new HashMap<>();
        for (ReflectTranscript.Line line : transcript.lines) {
            if (line.speakerName != null) names.putIfAbsent(line.speakerId, line.speakerName);
        }
        return names;
    }

    void link(File recording, ReflectTranscript transcript, String localId, String profileId) throws IOException {
        if (profileId == null) throw new IllegalArgumentException("Use a separate profile to split a speaker");
        if (transcript.lines.stream().noneMatch(line -> line.speakerId.equals(localId)))
            throw new IllegalArgumentException("Speaker is not in this transcript");
        String key = recordingKey(recording), revision = revision(transcript);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            migrateLegacyRevision(db, key, revision, transcript);
            // A split also protects the other side: it must not retry back into the new identity.
            String previous = readLinks(db, key, revision).get(localId);
            if (previous != null) SpeakerLinkPolicy.confirmProfile(db, previous);
            db.delete("links", "recording=? AND revision=? AND local_id=?", new String[]{key, revision, localId});
            insertLink(db, key, revision, localId, profileId);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void insertLink(SQLiteDatabase db, String recording, String revision, String local, String profile) {
        insertLink(db, recording, revision, local, profile, SpeakerLinkPolicy.MANUAL);
    }

    private static void insertLink(SQLiteDatabase db, String recording, String revision, String local, String profile,
                                   String origin) {
        ContentValues values = new ContentValues(); values.put("recording", recording); values.put("revision", revision);
        values.put("local_id", local); values.put("profile_id", profile); values.put("origin", origin);
        db.insertOrThrow("links", null, values);
    }

    /** Explicit split pins a fresh UUID, so the matcher cannot undo a user's correction. */
    void separate(File recording, ReflectTranscript transcript, String localId) throws IOException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Profile separate = insertProfile(db, nextName(profileNames()), true);
            link(recording, transcript, localId, separate.id);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    Map<String, String> assign(File recording, ReflectTranscript transcript) throws IOException {
        String key = recordingKey(recording), revision = revision(transcript);
        Set<String> localIds = new LinkedHashSet<>();
        for (var line : transcript.lines) localIds.add(line.speakerId);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            migrateLegacyRevision(db, key, revision, transcript);
            db.execSQL("INSERT OR IGNORE INTO active_transcripts(recording, revision) VALUES (?,?)",
                    new Object[]{key, revision});
            db.execSQL("UPDATE active_transcripts SET revision=? WHERE recording=? AND revision<>?",
                    new Object[]{revision, key, revision});
            Map<String, String> linked = readLinks(db, key, revision);
            localIds.removeAll(linked.keySet());
            Map<String, String> pending = SpeakerLinkPolicy.retryable(db, key, revision);
            localIds.addAll(pending.keySet());
            if (!localIds.isEmpty()) {
                Set<String> reserved = new HashSet<>();
                linked.forEach((local, profile) -> { if (!pending.containsKey(local)) reserved.add(profile); });
                var matches = SpeakerIdentityMatcher.match(db, key, revision, localIds, reserved,
                        new HashSet<>(pending.values()));
                Set<String> names = matches.size() == localIds.size() ? Set.of() : profileNames();
                Map<String, String> imported = importedNames(transcript);
                if (matches.size() != localIds.size()) names.addAll(imported.values());
                for (String local : localIds) {
                    String id = matches.get(local);
                    if (pending.containsKey(local)) {
                        if (id != null) {
                            db.execSQL("UPDATE links SET profile_id=?,origin='automatic' "
                                            + "WHERE recording=? AND revision=? AND local_id=? AND origin='pending'",
                                    new Object[]{id, key, revision, local});
                            linked.put(local, id);
                        }
                        continue;
                    }
                    String origin = id == null ? SpeakerLinkPolicy.PENDING : SpeakerLinkPolicy.AUTOMATIC;
                    if (id == null) {
                        String suppliedName = imported.get(local);
                        id = insertProfile(db, suppliedName != null ? suppliedName : nextName(names), true).id;
                    }
                    insertLink(db, key, revision, local, id, origin);
                    linked.put(local, id);
                }
            }
            // A core may already have created placeholder profiles before LLM refinement arrived.
            // User names, marked-self profiles and existing real names always take priority.
            for (var supplied : importedNames(transcript).entrySet()) {
                String id = linked.get(supplied.getKey());
                if (id == null) continue;
                Profile profile = get(db, id);
                if (profile.automatic && !profile.self && profile.name.matches("Спикер [1-9][0-9]*")) {
                    ContentValues values = new ContentValues(); values.put("name", checkedName(supplied.getValue()));
                    db.update("profiles", values, "id=? AND automatic=1 AND is_self=0", new String[]{id});
                }
            }
            db.setTransactionSuccessful();
            return linked;
        } finally { db.endTransaction(); }
    }

    /** An engine-owned refinement uses the same acoustic labels as its provisional transcript. */
    void retainAcrossRefinement(File recording, ReflectTranscript provisional, ReflectTranscript refined)
            throws IOException {
        String key = recordingKey(recording), before = revision(provisional), after = revision(refined);
        if (before.equals(after)) return;
        Set<String> localIds = new HashSet<>();
        for (var line : refined.lines) localIds.add(line.speakerId);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String localId : localIds) {
                db.execSQL("INSERT OR IGNORE INTO links(recording, revision, local_id, profile_id, origin) "
                                + "SELECT recording, ?, local_id, profile_id, origin FROM links "
                                + "WHERE recording=? AND revision=? AND local_id=?",
                        new Object[]{after, key, before, localId});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private Set<String> profileNames() {
        Set<String> names = new HashSet<>();
        for (Profile profile : list()) names.add(profile.name);
        return names;
    }

    private static String nextName(Set<String> names) {
        for (int i = 1; ; i++) {
            String name = "Спикер " + i;
            if (names.add(name)) return name;
        }
    }

    void forgetActive(String recording) {
        getWritableDatabase().delete("active_transcripts", "recording=?", new String[]{recording});
    }

    void pruneActive(Set<String> existingRecordings) {
        SQLiteDatabase db = getWritableDatabase();
        List<String> missing = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT recording FROM active_transcripts", null)) {
            while (rows.moveToNext()) if (!existingRecordings.contains(rows.getString(0))) missing.add(rows.getString(0));
        }
        for (String recording : missing) forgetActive(recording);
    }

    private static void migrateLegacyRevision(SQLiteDatabase db, String recording, String revision,
                                              ReflectTranscript transcript) throws IOException {
        if (!hasEmotions(transcript)) return;
        String legacy = hash(transcript.encode());
        if (legacy.equals(revision)) return;
        for (String table : List.of("links", "embeddings")) {
            db.execSQL("UPDATE OR IGNORE " + table + " SET revision=? WHERE recording=? AND revision=?",
                    new Object[]{revision, recording, legacy});
            db.delete(table, "recording=? AND revision=?", new String[]{recording, legacy});
        }
    }

    private static boolean hasEmotions(ReflectTranscript transcript) {
        return transcript.lines.stream().anyMatch(line -> line.emotionProbabilities != null);
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
        return hash(transcript.encodeSpeakerRevision());
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
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
