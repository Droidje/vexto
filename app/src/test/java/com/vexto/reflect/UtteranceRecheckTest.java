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

    @Test public void shortNeighborInsideTheWindowStaysUntouched() {
        ReflectTranscript.Line previous = line("speaker_1", 1_600, 2_100, "угу", CALM);
        ReflectTranscript.Line selected = line("speaker_2", 2_200, 4_000, "основная", CALM);
        ReflectTranscript.Line following = line("speaker_1", 8_000, 9_000, "потом", ANGER);
        ReflectTranscript original = transcript(previous, selected, following);
        ReflectTranscript updated = UtteranceRecheck.apply(original, selected, 700, 5_500, List.of(
                piece(1_620, 2_050, "ага", JOY),
                piece(2_250, 3_800, "пересчитано", ANGER),
                piece(4_100, 4_700, "следующая", JOY)));
        assertEquals(3, updated.lines.size());
        assertEquals("угу", updated.lines.get(0).text);
        assertSame(CALM, updated.lines.get(0).emotionProbabilities);
        assertEquals("speaker_2", updated.lines.get(1).speakerId);
        assertEquals("пересчитано", updated.lines.get(1).text);
        assertSame(ANGER, updated.lines.get(1).emotionProbabilities);
        assertEquals("потом", updated.lines.get(2).text);
        assertSame(ANGER, updated.lines.get(2).emotionProbabilities);
    }

    @Test public void wordsCutTheNeighborTailOutOfTheSelectedLine() {
        ReflectTranscript.Line previous = line("speaker_1", 4_600, 8_500, "предыдущая", CALM);
        ReflectTranscript.Line selected = line("speaker_2", 8_700, 28_000, "старая длинная", CALM, "Маша");
        ReflectTranscript.Line following = line("speaker_3", 28_700, 30_000, "груши. Точно, да?", JOY);
        ReflectTranscript original = transcript(previous, selected, following);
        ReflectTranscript updated = UtteranceRecheck.apply(original, selected, 6_500, 29_500, List.of(
                piece(7_300, 29_900, "Он ехал дальше. Основная фраза. А, точно, да?", ANGER, List.of(
                        word(7_300, 7_600, "Он"),
                        word(7_600, 7_900, "ехал"),
                        word(8_500, 20_000, "Основная"),
                        word(20_100, 27_800, "фраза."),
                        word(28_800, 29_200, "А,"),
                        word(29_300, 29_800, "точно")))));
        assertEquals(3, updated.lines.size());
        assertEquals("предыдущая", updated.lines.get(0).text);
        assertEquals("Основная фраза.", updated.lines.get(1).text);
        assertEquals("speaker_2", updated.lines.get(1).speakerId);
        assertEquals("Маша", updated.lines.get(1).speakerName);
        assertEquals(8_500, updated.lines.get(1).startMs);
        assertEquals(27_800, updated.lines.get(1).endMs);
        assertSame(ANGER, updated.lines.get(1).emotionProbabilities);
        assertEquals("груши. Точно, да?", updated.lines.get(2).text);
        assertEquals("speaker_3", updated.lines.get(2).speakerId);
        assertSame(JOY, updated.lines.get(2).emotionProbabilities);
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

    @Test public void repeatedBoundaryWordStaysOnlyInTheRecheckedLine() {
        ReflectTranscript.Line selected = line("speaker_1", 8_000, 28_800, "старая", CALM);
        ReflectTranscript.Line following = line("speaker_3", 28_700, 30_000, "груши. Точно, да?", JOY);
        ReflectTranscript updated = UtteranceRecheck.apply(transcript(selected, following), selected, 6_500, 30_000, List.of(
                piece(8_100, 29_031, "даёт три груши.", ANGER, List.of(
                        word(8_100, 27_000, "даёт"),
                        word(27_100, 28_000, "три"),
                        word(28_100, 29_031, "груши.")))));
        assertEquals(2, updated.lines.size());
        assertEquals("даёт три груши.", updated.lines.get(0).text);
        assertEquals(29_031, updated.lines.get(0).endMs);
        assertEquals("Точно, да?", updated.lines.get(1).text);
        assertEquals("speaker_3", updated.lines.get(1).speakerId);
        assertEquals(29_031, updated.lines.get(1).startMs);
        assertEquals(30_000, updated.lines.get(1).endMs);
        assertSame(JOY, updated.lines.get(1).emotionProbabilities);
    }

    @Test public void repeatedOpeningWordIsNotCopiedFromThePreviousLine() {
        ReflectTranscript.Line previous = line("speaker_1", 4_000, 8_200, "он ехал дальше.", CALM);
        ReflectTranscript.Line selected = line("speaker_2", 8_000, 12_000, "старая", CALM);
        ReflectTranscript updated = UtteranceRecheck.apply(transcript(previous, selected), selected, 6_500, 13_000, List.of(
                piece(7_900, 11_000, "дальше. Основная фраза", ANGER, List.of(
                        word(7_900, 8_300, "дальше."),
                        word(8_400, 11_000, "Основная")))));
        assertEquals(2, updated.lines.size());
        assertEquals("он ехал дальше.", updated.lines.get(0).text);
        assertEquals(4_000, updated.lines.get(0).startMs);
        assertEquals("Основная", updated.lines.get(1).text);
        assertEquals("speaker_2", updated.lines.get(1).speakerId);
    }

    @Test public void onePieceThatSwallowsANeighborIsNotAppliedWithoutWords() {
        ReflectTranscript.Line first = line("speaker_1", 1_000, 1_400, "а", CALM);
        ReflectTranscript.Line selected = line("speaker_2", 1_500, 3_500, "б", CALM);
        ReflectTranscript original = transcript(first, selected);
        assertNull(UtteranceRecheck.apply(original, selected, 0, 4_000, List.of(
                piece(1_000, 3_400, "одна фраза", JOY))));
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

    private static UtteranceRecheck.Piece piece(int start, int end, String text, EmotionProbabilities emotion,
                                                List<UtteranceRecheck.TimedWord> words) {
        return new UtteranceRecheck.Piece(start, end, text, emotion, words);
    }

    private static UtteranceRecheck.TimedWord word(int start, int end, String text) {
        return new UtteranceRecheck.TimedWord(start, end, text);
    }
}
