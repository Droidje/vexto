package com.vexto.reflect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ReflectSummarySourceTest {
    @Test public void keepsCompleteLongDialogueAndStableSpeakerNumbersWithoutProfileNames() {
        String beginning = "Начало обсуждения.\nНужные факты. ".repeat(100);
        String ending = "Решение принято в конце разговора.";
        var source = ReflectSummaryController.sourceOf(new ReflectTranscript(List.of(
                new ReflectTranscript.Line("global-b", 0, 10, beginning, null, "Private profile"),
                new ReflectTranscript.Line("global-a", 10, 20, ending),
                new ReflectTranscript.Line("global-b", 20, 30, "Подтверждаю."))));

        assertEquals("Участник 1: " + beginning.replace('\n', ' ') + "\nУчастник 2: " + ending
                + "\nУчастник 1: Подтверждаю.\n", source.text());
        assertFalse(source.text().contains("Private profile"));
        assertTrue(source.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test public void metadataEnrichmentRetainsCacheButChangedWordsInvalidateIt() {
        var initial = source(new ReflectTranscript.Line("global-a", 0, 10, "Решение принято."));
        var enriched = source(new ReflectTranscript.Line("global-a", 0, 10, "Решение принято.",
                null, "Renamed speaker", true));
        var corrected = source(new ReflectTranscript.Line("global-a", 0, 10, "Решение не принято."));
        assertEquals(initial, enriched);
        assertNotEquals(initial.fingerprint(), corrected.fingerprint());
    }

    private static ReflectSummaryController.Source source(ReflectTranscript.Line line) {
        return ReflectSummaryController.sourceOf(new ReflectTranscript(List.of(line)));
    }
}
