package com.vexto.reflect;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Conservative Android port of the research cosine, threshold and margin matcher. */
final class SpeakerIdentityMatcher {
    // The policy only applies to the bundled ERes2Net checkpoint and this signature pipeline.
    static final String PROFILE_MODEL = "sherpa-2.0.1:pcm16-16k-agc-v1:l2-centroid-v1:"
            + "e2d2048292e055f7b61cdec3db010503f35369b245bf0b3bbad021c9a91e4053";
    private static final String MIN_SPEECH_MS = "5000";
    private static final double MIN_SCORE = 0.80;
    private static final double MIN_MARGIN = 0.08;

    private SpeakerIdentityMatcher() {}

    /** The caller supplies the transaction so the prototype set is frozen for the recording. */
    static Map<String, String> match(SQLiteDatabase db, String recording, String revision,
                                     Set<String> unlinkedLocalIds, Set<String> usedProfileIds) {
        return match(db, recording, revision, unlinkedLocalIds, usedProfileIds, Set.of());
    }

    static Map<String, String> match(SQLiteDatabase db, String recording, String revision,
                                     Set<String> unlinkedLocalIds, Set<String> usedProfileIds,
                                     Set<String> excludedProfileIds) {
        if (unlinkedLocalIds.isEmpty()) return Map.of();
        Map<String, float[]> targets = new LinkedHashMap<>();
        try (Cursor rows = db.rawQuery("SELECT local_id, dimension, vector FROM embeddings "
                + "WHERE recording=? AND revision=? AND model_id=? AND speech_ms>=? ORDER BY local_id",
                new String[]{recording, revision, PROFILE_MODEL, MIN_SPEECH_MS})) {
            while (rows.moveToNext()) {
                String localId = rows.getString(0);
                if (unlinkedLocalIds.contains(localId)) readVector(rows, localId, rows.getInt(1), 2, targets);
            }
        }
        Map<Integer, Map<String, float[]>> prototypes = new HashMap<>();
        for (float[] target : targets.values()) {
            prototypes.computeIfAbsent(target.length, dimension -> loadPrototypes(db, recording, dimension));
        }
        for (var gallery : prototypes.values()) gallery.keySet().removeAll(excludedProfileIds);
        return matchSignatures(targets, prototypes, usedProfileIds);
    }

    private static Map<String, float[]> loadPrototypes(SQLiteDatabase db, String recording, int dimension) {
        Map<String, float[]> result = new LinkedHashMap<>();
        // One earliest eligible signature per profile, from current revisions of other recordings.
        try (Cursor rows = db.rawQuery("SELECT first.profile_id, prototype.vector FROM ("
                + "SELECT l.profile_id, MIN(e.rowid) AS prototype_id FROM embeddings e "
                + "JOIN links l ON e.recording=l.recording AND e.revision=l.revision AND e.local_id=l.local_id "
                + "JOIN active_transcripts a ON a.recording=e.recording AND a.revision=e.revision "
                + "WHERE e.recording<>? AND e.model_id=? AND e.dimension=? AND e.speech_ms>=? "
                + "AND length(e.vector)=e.dimension*4 GROUP BY l.profile_id"
                + ") first JOIN embeddings prototype ON prototype.rowid=first.prototype_id ORDER BY first.profile_id",
                new String[]{recording, PROFILE_MODEL, String.valueOf(dimension), MIN_SPEECH_MS})) {
            while (rows.moveToNext()) readVector(rows, rows.getString(0), dimension, 1, result);
        }
        return result;
    }

    private static void readVector(Cursor row, String id, int dimension, int column, Map<String, float[]> vectors) {
        try { vectors.put(id, VoiceVectors.decode(row.getBlob(column), dimension)); }
        catch (IllegalArgumentException invalid) { /* Unusable signatures cannot enroll or match. */ }
    }

    /** Inputs are normalized; every local speaker is scored against the same prototype snapshot. */
    static Map<String, String> matchSignatures(Map<String, float[]> targets,
                                               Map<Integer, Map<String, float[]>> prototypes,
                                               Set<String> usedProfileIds) {
        Map<String, String> claims = new LinkedHashMap<>();
        Map<String, Integer> claimCounts = new HashMap<>();
        targets.forEach((localId, vector) -> {
            String profileId = bestProfile(vector, prototypes.getOrDefault(vector.length, Map.of()));
            if (profileId != null && !usedProfileIds.contains(profileId)) {
                claims.put(localId, profileId);
                claimCounts.merge(profileId, 1, Integer::sum);
            }
        });
        claims.values().removeIf(profile -> claimCounts.get(profile) > 1);
        return claims;
    }

    private static String bestProfile(float[] target, Map<String, float[]> prototypes) {
        String bestProfile = null;
        double best = Double.NEGATIVE_INFINITY;
        double next = Double.NEGATIVE_INFINITY;
        for (var prototype : prototypes.entrySet()) {
            double score = VoiceVectors.cosine(target, prototype.getValue());
            if (score > best) {
                next = best;
                best = score;
                bestProfile = prototype.getKey();
            } else if (score > next) {
                next = score;
            }
        }
        return best >= MIN_SCORE && best - next >= MIN_MARGIN ? bestProfile : null;
    }
}
