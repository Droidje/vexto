package com.vexto.reflect;

import com.vexto.engine.model.EmotionProbabilities;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class UtteranceRecheckTest {
    private static final EmotionProbabilities CALM = new EmotionProbabilities(1, 0, 0, 0);
    private static final EmotionProbabilities JOY = new EmotionProbabilities(0.1, 0.7, 0.1, 0.1);
    private static final EmotionProbabilities ANGER = new EmotionProbabilities(0.1, 0.1, 0.1, 0.7);

    @Test public void windowPadsInsideTheRecording() {
        UtteranceRecheck.Window window = UtteranceRecheck.window(3_000, 4_000, 20_000);
        assertEquals(1_500, window.startMs);
        assertEquals(5_500, window.endMs);
    }

    @Test public void windowClampsToTheFileEdges() {
        UtteranceRecheck.Window window = UtteranceRecheck.window(100, 300, 500);
        assertEquals(0, window.startMs);
        assertEquals(500, window.endMs);
        assertNull(UtteranceRecheck.window(100, 300, 0));
        assertNull(UtteranceRecheck.window(500, 800, 500));
    }

    @Test public void onePieceReplacesTextTimesAndEmotion() {
        ReflectTranscript.Line selected = line("speaker_2", 1_000, 2_000, "старый", CALM);
        ReflectTranscript original = transcript(line("speaker_1", 0, 400, "далеко", CALM), selected);
        ReflectTranscript updated = UtteranceRecheck.apply(original, selected, 550, 2_450, List.of(
                piece(1_050, 1_900, "новый текст", JOY)));
        assertEquals(2, updated.lines.size());
        assertEquals("далеко", updated.lines.get(0).text);
        assertSame(CALM, updated.lines.get(0).emotionProbabilities);
        ReflectTranscript.Line replaced = updated.lines.get(1);
        assertEquals("speaker_2", replaced.speakerId);
        assertEquals("новый текст", replaced.text);
        assertEquals(1_050, replaced.startMs);
        assertEquals(1_900, replaced.endMs);
        assertSame(JOY, replaced.emotionProbabilities);
    }

    @Test public void splitIntoThreePiecesKeepsTheSelectedSpeaker() {
        ReflectTranscript.Line selected = line("speaker_2", 1_000, 4_000, "длинная фраза", CALM, "Маша");
        ReflectTranscript original = transcript(selected);
        ReflectTranscript updated = UtteranceRecheck.apply(original, selected, 550, 4_450, List.of(
                piece(1_000, 1_800, "раз", JOY),
                piece(1_800, 2_600, "два", ANGER),
                piece(2_600, 3_900, "три", null)));
        assertEquals(3, updated.lines.size());
        for (ReflectTranscript.Line line : updated.lines) {
            assertEquals("speaker_2", line.speakerId);
            assertEquals("Маша", line.speakerName);
        }
        assertEquals("раз", updated.lines.get(0).text);
        assertEquals("два", updated.lines.get(1).text);
        assertEquals("три", updated.lines.get(2).text);
        assertSame(JOY, updated.lines.get(0).emotionProbabilities);
        assertNull(updated.lines.get(2).emotionProbabilities);
    }

    @Test public void shortNeighborInsideTheWindowIsReplacedToo() {
        ReflectTranscript.Line previous = line("speaker_1", 1_600, 2_100, "угу", CALM);
        ReflectTranscript.Line selected = line("speaker_2", 2_200, 4_000, "основная", CALM);
        ReflectTranscript original = transcript(previous, selected,
                line("speaker_1", 8_000, 9_000, "потом", ANGER));
        ReflectTranscript updated = UtteranceRecheck.apply(original, selected, 1_750, 4_450, List.of(
                piece(1_620, 2_050, "ага", JOY),
                piece(2_250, 3_800, "пересчитано", ANGER)));
        assertEquals(3, updated.lines.size());
        assertEquals("speaker_1", updated.lines.get(0).speakerId);
        assertEquals("ага", updated.lines.get(0).text);
        assertSame(JOY, updated.lines.get(0).emotionProbabilities);
        assertEquals("speaker_2", updated.lines.get(1).speakerId);
        assertEquals("пересчитано", updated.lines.get(1).text);
        assertEquals("потом", updated.lines.get(2).text);
        assertSame(ANGER, updated.lines.get(2).emotionProbabilities);
    }

    @Test public void longNeighborKeepsItsTextWhenOnlyASliverWasHeard() {
        ReflectTranscript.Line previous = line("speaker_1", 0, 3_000, "длинная предыдущая", CALM);
        ReflectTranscript.Line selected = line("speaker_2", 3_200, 5_000, "выбранная", CALM);
        ReflectTranscript original = transcript(previous, selected);
        ReflectTranscript updated = UtteranceRecheck.apply(original, selected, 2_750, 5_450, List.of(
                piece(2_760, 3_050, "хвост предыдущей", JOY),
                piece(3_220, 4_900, "новая выбранная", ANGER)));
        assertEquals(2, updated.lines.size());
        assertEquals("длинная предыдущая", updated.lines.get(0).text);
        assertSame(CALM, updated.lines.get(0).emotionProbabilities);
        assertEquals("speaker_2", updated.lines.get(1).speakerId);
        assertEquals("новая выбранная", updated.lines.get(1).text);
        assertSame(ANGER, updated.lines.get(1).emotionProbabilities);
    }

    @Test public void emptyBlankAndUncoveredResultsDoNotReplace() {
        ReflectTranscript.Line previous = line("speaker_1", 0, 3_000, "предыдущая", CALM);
        ReflectTranscript.Line selected = line("speaker_2", 3_200, 5_000, "выбранная", CALM);
        ReflectTranscript original = transcript(previous, selected);
        assertNull(UtteranceRecheck.apply(original, selected, 2_750, 5_450, List.of()));
        assertNull(UtteranceRecheck.apply(original, selected, 2_750, 5_450, List.of(piece(3_300, 4_000, "   ", JOY))));
        assertNull(UtteranceRecheck.apply(original, selected, 2_750, 5_450, null));
        assertNull(UtteranceRecheck.apply(original, selected, 2_750, 5_450,
                List.of(piece(2_760, 3_050, "только сосед", JOY))));
    }

    @Test public void identicalResultDoesNotRewriteTheTranscript() {
        ReflectTranscript.Line selected = line("speaker_2", 1_000, 2_000, "тот же", CALM);
        ReflectTranscript original = transcript(selected);
        assertSame(original, UtteranceRecheck.apply(original, selected, 550, 2_450,
                List.of(piece(1_000, 2_000, "тот же", CALM))));
    }

    @Test public void missingSelectedLineDoesNotReplace() {
        ReflectTranscript.Line selected = line("speaker_2", 1_000, 2_000, "нет в тексте", CALM);
        ReflectTranscript original = transcript(line("speaker_1", 0, 500, "другая", CALM));
        assertNull(UtteranceRecheck.apply(original, selected, 550, 2_450,
                List.of(piece(1_000, 1_800, "опоздание", JOY))));
    }

    @Test public void twoNeighborsMergeIntoTheSpeakerWithMoreOverlap() {
        ReflectTranscript.Line first = line("speaker_1", 1_000, 1_400, "а", CALM);
        ReflectTranscript.Line selected = line("speaker_2", 1_500, 3_500, "б", CALM);
        ReflectTranscript original = transcript(first, selected);
        ReflectTranscript updated = UtteranceRecheck.apply(original, selected, 550, 3_950, List.of(
                piece(1_000, 3_400, "одна фраза", JOY)));
        assertEquals(1, updated.lines.size());
        assertEquals("speaker_2", updated.lines.get(0).speakerId);
        assertEquals("одна фраза", updated.lines.get(0).text);
        assertSame(JOY, updated.lines.get(0).emotionProbabilities);
    }

    private static ReflectTranscript transcript(ReflectTranscript.Line... lines) {
        return new ReflectTranscript(List.of(lines));
    }

    private static ReflectTranscript.Line line(String speaker, int start, int end, String text,
                                               EmotionProbabilities emotion) {
        return new ReflectTranscript.Line(speaker, start, end, text, emotion, null);
    }

    private static ReflectTranscript.Line line(String speaker, int start, int end, String text,
                                               EmotionProbabilities emotion, String name) {
        return new ReflectTranscript.Line(speaker, start, end, text, emotion, name);
    }

    private static UtteranceRecheck.Piece piece(int start, int end, String text, EmotionProbabilities emotion) {
        return new UtteranceRecheck.Piece(start, end, text, emotion);
    }
}
