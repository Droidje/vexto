package com.vexto.reflect;

import static org.junit.Assert.*;
import android.content.Intent;
import com.vexto.engine.model.EmotionProbabilities;
import com.vexto.engine.model.export.TranscriptExport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RecordingExportTest {
    private static final EmotionProbabilities EMOTIONS = new EmotionProbabilities(.25, .5, .125, .125);

    private static ReflectTranscript conversation(int count) {
        List<ReflectTranscript.Line> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(new ReflectTranscript.Line("speaker_" + (i % 7), i * 800, i * 800 + 1200,
                    "Реплика " + i + " — полный текст \"да\" 😀\nВторая строка *важно* | <текст>",
                    i % 2 == 0 ? EMOTIONS : null));
        }
        return new ReflectTranscript(lines);
    }

    private static byte[] export(RecordingExport format, ReflectTranscript transcript) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        format.write(out, null, transcript, "Обсуждение *задачи*", Map.of("speaker_0", "Алиса", "speaker_1", "Алиса"));
        return out.toByteArray();
    }

    @Test public void jsonMatchesTheFourFieldContractWithoutLosingAnyUtterance() throws Exception {
        ReflectTranscript source = conversation(137);
        byte[] data = export(RecordingExport.JSON, source);
        ReflectTranscript parsed = ReflectTranscript.parse(new ByteArrayInputStream(data));
        assertEquals(137, parsed.lines.size());
        assertEquals(7, parsed.lines.stream().map(line -> line.speakerId).distinct().count());
        var rows = new org.json.JSONObject(new String(data, StandardCharsets.UTF_8)).getJSONArray("transcript");
        for (int i = 0; i < source.lines.size(); i++) {
            var expected = source.lines.get(i);
            var actual = parsed.lines.get(i);
            assertEquals(expected.speakerId, actual.speakerId);
            assertEquals(expected.startMs, actual.startMs);
            assertEquals(expected.endMs, actual.endMs);
            assertEquals(expected.text, actual.text);
            var keys = new java.util.HashSet<String>();
            rows.getJSONObject(i).keys().forEachRemaining(keys::add);
            assertEquals(java.util.Set.of("speaker_id", "start_ms", "end_ms", "text"), keys);
            assertNull(actual.emotionProbabilities);
            assertNull(actual.speakerName);
        }
        assertTrue(parsed.sameLines(ReflectTranscript.parse(new ByteArrayInputStream(parsed.encode()))));
        var engineParsed = TranscriptExport.parseJson(new String(data, StandardCharsets.UTF_8));
        assertEquals(137, engineParsed.size());
        assertNull(engineParsed.get(0).getSpeakerName());
        assertNull(engineParsed.get(0).getEmotionProbabilities());
    }

    @Test public void optionalExportDetailsDoNotChangeExistingSpeakerLinks() throws Exception {
        var plain = new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1", 0, 1000, "Текст")));
        var enriched = new ReflectTranscript(List.of(new ReflectTranscript.Line(
                "speaker_1", 0, 1000, "Текст", EMOTIONS, "Алиса")));

        assertArrayEquals(plain.encode(), enriched.encodeSpeakerRevision());
        byte[] stored = enriched.encode();
        assertTrue(enriched.sameLines(ReflectTranscript.parse(new ByteArrayInputStream(stored))));
        var legacy = TranscriptExport.parseJson(new String(stored, StandardCharsets.UTF_8)).get(0);
        assertEquals("Алиса", legacy.getSpeakerName());
        assertEquals(EMOTIONS.ordered(), legacy.getEmotionProbabilities());
    }

    @Test public void markdownIncludesAllIdsEvenForIdenticalNamesAndAllProbabilityValues() throws Exception {
        String md = new String(export(RecordingExport.MARKDOWN, conversation(137)), StandardCharsets.UTF_8);
        for (int i = 0; i < 137; i++) assertTrue(md.contains("Реплика " + i + " — полный текст"));
        for (int i = 0; i < 7; i++) assertTrue(md.contains("speaker\\_" + i));
        assertTrue(md.contains("Алиса \\(speaker\\_0\\)"));
        assertTrue(md.contains("00:00:00.000 – 00:00:01.200"));
        assertTrue(md.contains("нейтральная=0.25; радость=0.5; грусть=0.125; злость=0.125"));
        assertTrue(md.contains("\n> Вторая строка \\*важно\\* \\| \\<текст\\>"));
        assertEquals(69, md.split("Эмоции ", -1).length - 1);
    }

    @Test public void previewIsBoundedButExportIsComplete() throws Exception {
        ReflectTranscript source = conversation(137);
        String preview = ReflectExportController.previewText(RecordingExport.JSON, source, "Запись", Map.of());
        assertTrue(preview.contains("Предпросмотр сокращён"));
        assertFalse(preview.contains("Реплика 136"));
        assertTrue(new String(export(RecordingExport.JSON, source), StandardCharsets.UTF_8).contains("Реплика 136"));
    }

    @Test public void acceptedTranscriptStillExportsWhenFormattingExceedsImportByteBudget() throws Exception {
        List<ReflectTranscript.Line> lines = new ArrayList<>();
        String words = "x".repeat(65455);
        for (int i = 0; i < 128; i++) lines.add(new ReflectTranscript.Line("speaker_0", i, i + 1, words));
        ReflectTranscript source = new ReflectTranscript(lines);
        byte[] stored = source.encode();
        assertTrue(stored.length <= 8 * 1024 * 1024);
        ReflectTranscript loaded = ReflectTranscript.parse(new ByteArrayInputStream(stored));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RecordingExport.JSON.write(out, null, loaded, "Запись", Map.of("speaker_0", "А".repeat(120)));
        assertTrue(out.size() > 8 * 1024 * 1024);
        var result = new org.json.JSONObject(out.toString(StandardCharsets.UTF_8)).getJSONArray("transcript");
        assertEquals(128, result.length());
        assertEquals(words, result.getJSONObject(127).getString("text"));
        assertFalse(result.getJSONObject(127).has("speaker_name"));
    }

    @Test public void legacyJsonHasNoInventedNamesOrEmotionsAndUnknownFieldsStillFail() throws Exception {
        var legacy = new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1", 0, 1000, "Текст")));
        String json = new String(legacy.encode(), StandardCharsets.UTF_8);
        assertFalse(json.contains("speaker_name"));
        assertFalse(json.contains("emotion_probabilities"));
        String unknown = json.replace("\"text\"", "\"unknown\"");
        assertThrows(java.io.IOException.class, () -> ReflectTranscript.parse(
                new ByteArrayInputStream(unknown.getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void exportShowsBasicPhoneticsWhileTheSavedTranscriptKeepsTheMarks() throws Exception {
        var source = new ReflectTranscript(List.of(new ReflectTranscript.Line(
                "speaker_1", 0, 1000, "пауза (ə) и #пеньк-пеньк#")));
        String json = new String(export(RecordingExport.JSON, source), StandardCharsets.UTF_8);
        String markdown = new String(export(RecordingExport.MARKDOWN, source), StandardCharsets.UTF_8);
        String stored = new String(source.encode(), StandardCharsets.UTF_8);
        assertTrue(json.contains("пауза э и пеньк пеньк"));
        assertFalse(json.contains("ə"));
        assertTrue(markdown.contains("пауза э и пеньк пеньк"));
        assertFalse(markdown.contains("(ə)"));
        assertTrue(stored.contains("(ə)"));
        assertTrue(stored.contains("#пеньк-пеньк#"));
        assertEquals("пауза (ə) и #пеньк-пеньк#", source.lines.get(0).text);
    }

    @Test public void audioIsCopiedByteForByteAndDocumentHasCorrectMimeAndSafeName() throws Exception {
        var path = Files.createTempFile("export-audio-", ".wav");
        try {
            byte[] audio = {0, 1, -1, 2, 100, 0};
            Files.write(path, audio);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            RecordingExport.AUDIO.write(out, path.toFile(), null, "Audio");
            assertArrayEquals(audio, out.toByteArray());
            var metadata = new RecordingMetadata(null, false, "", audio.length, "audio/wav", "wav", null);
            Intent intent = RecordingExport.AUDIO.createDocument(metadata, "../Запись:1");
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
            assertEquals("audio/wav", intent.getType());
            assertEquals(".._Запись_1.wav", intent.getStringExtra(Intent.EXTRA_TITLE));
        } finally { Files.delete(path); }
    }
}
