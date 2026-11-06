package com.vexto.reflect;

import org.junit.Test;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpeakerIdentityMatcherTest {
    private static final float[] TARGET = {1, 0};

    @Test public void acceptsClearCrossRecordingCandidate() {
        assertEquals(Map.of("speaker_1", "closest"), score(Map.of(
                "closest", new float[]{0.90f, 0.436f}, "other", new float[]{0.70f, 0.714f})));
    }

    @Test public void rejectsWeakOrAmbiguousCandidate() {
        assertTrue(score(Map.of("weak", new float[]{0.79f, 0.613f})).isEmpty());
        assertTrue(score(Map.of("a", new float[]{0.90f, 0.436f}, "b", new float[]{0.85f, 0.527f})).isEmpty());
        assertTrue(score(Map.of("a", TARGET, "b", TARGET)).isEmpty());
    }

    @Test public void duplicateClaimsInOneRecordingAreBothRejected() {
        assertTrue(SpeakerIdentityMatcher.matchSignatures(
                Map.of("speaker_1", TARGET, "speaker_2", TARGET), Map.of(2, Map.of("a", TARGET)), Set.of())
                .isEmpty());
    }

    @Test public void reservedBestProfileDoesNotRedirectToSecondBest() {
        assertTrue(SpeakerIdentityMatcher.matchSignatures(Map.of("speaker_1", TARGET),
                Map.of(2, Map.of("reserved", TARGET, "second", new float[]{0.85f, 0.527f})), Set.of("reserved"))
                .isEmpty());
    }

    @Test public void onlyMatchingDimensionsAreConsidered() {
        assertTrue(SpeakerIdentityMatcher.matchSignatures(Map.of("speaker_1", TARGET),
                Map.of(1, Map.of("a", new float[]{1})), Set.of()).isEmpty());
    }

    private static Map<String, String> score(Map<String, float[]> candidates) {
        return SpeakerIdentityMatcher.matchSignatures(Map.of("speaker_1", TARGET), Map.of(2, candidates), Set.of());
    }
}
