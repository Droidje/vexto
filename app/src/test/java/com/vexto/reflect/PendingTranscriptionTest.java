package com.vexto.reflect;

import android.content.Context;
import com.vexto.engine.model.EmotionProbabilities;
import com.vexto.engine.model.Transcript;
import com.vexto.engine.model.Utterance;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PendingTranscriptionTest {
    private Context context;
    private File recording;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        File recordings = new File(context.getFilesDir(), "recordings");
        Files.createDirectories(recordings.toPath());
        recording = Files.write(new File(recordings, "speech.wav").toPath(), new byte[]{1}).toFile();
    }

    @Test public void recoversSameSavedRecordingAndJobIdUntilCancelled() throws Exception {
        var request = PendingTranscription.create(context, recording);
        request.write(context);

        assertEquals(request, PendingTranscription.read(context));
        assertEquals(recording, PendingTranscription.read(context).recording());

        PendingTranscription.clear(context);
        assertNull(PendingTranscription.read(context));
    }

    @Test public void rejectsDraftAndForgedRecordingPath() throws Exception {
        File draft = Files.write(new File(context.getCacheDir(), recording.getName()).toPath(), new byte[]{1}).toFile();
        assertThrows(IOException.class, () -> PendingTranscription.create(context, draft));

        var request = PendingTranscription.create(context, recording);
        File descriptor = new File(context.getNoBackupFilesDir(), "pending-transcription.json");
        String forged = "{\"jobId\":\"" + request.jobId() + "\",\"recording\":\"../speech.wav\"}";
        Files.write(descriptor.toPath(), forged.getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PendingTranscription.read(context));
    }

    @Test public void recoveredJobRefinesItsCoreAndRetriesTheFinalPublicationIdempotently() throws Exception {
        String id = PendingTranscription.create(context, recording).jobId();
        var first = new ReflectTranscriptStore(context.getFilesDir());
        assertTrue(first.saveEngineCore(recording, id, transcript("Промежуточный текст")));

        var reopened = new ReflectTranscriptStore(context.getFilesDir());
        var saved = reopened.saveEngineFinal(recording, id, transcript("Готовый текст"));
        assertTrue(saved.owned());
        assertEquals("Промежуточный текст", saved.provisional().lines.get(0).text);
        assertEquals("Готовый текст", reopened.load(recording).lines.get(0).text);
        var retried = reopened.saveEngineFinal(recording, id, transcript("Другая попытка"));
        assertTrue(retried.owned());
        assertEquals("Готовый текст", retried.transcript().lines.get(0).text);
    }

    @Test public void emptyDiarizationIntervalsDoNotBlockCoreOrFinalPublication() throws Exception {
        var emotions = new EmotionProbabilities(1, 0, 0, 0);
        var output = new Transcript(List.of(
                new Utterance("speaker_1", 0, 100, "", List.of(), null),
                new Utterance("speaker_1", 100, 200, "  Полный исходный текст.  ", List.of(), emotions),
                new Utterance("speaker_2", 200, 300, " \t\n", List.of(), null),
                new Utterance("speaker_2", 300, 400, "Вторая реплика.", List.of(), null),
                new Utterance("speaker_1", 400, 500, "", List.of(), null)), List.of());
        var converted = ReflectTranscriptionService.convert(output);
        String id = PendingTranscription.create(context, recording).jobId();
        var store = new ReflectTranscriptStore(context.getFilesDir());
        assertTrue(store.saveEngineCore(recording, id, converted));
        assertTrue(store.saveEngineFinal(recording, id, converted).owned());

        var restored = new ReflectTranscriptStore(context.getFilesDir()).load(recording);
        assertEquals(2, restored.lines.size());
        assertEquals("  Полный исходный текст.  ", restored.lines.get(0).text);
        assertEquals(100, restored.lines.get(0).startMs);
        assertEquals(200, restored.lines.get(0).endMs);
        assertEquals(emotions, restored.lines.get(0).emotionProbabilities);
        assertEquals("speaker_2", restored.lines.get(1).speakerId);
        assertEquals("Вторая реплика.", restored.lines.get(1).text);
        assertEquals(5, output.getUtterances().size()); // Engine evidence is not changed.
    }

    @Test public void recoveredJobDoesNotOverwriteUserTranscriptOrAnotherJobsCore() throws Exception {
        String id = PendingTranscription.create(context, recording).jobId();
        var store = new ReflectTranscriptStore(context.getFilesDir());
        assertTrue(store.saveEngineCore(recording, id, transcript("Промежуточный текст")));

        String differentId = PendingTranscription.create(context, recording).jobId();
        assertFalse(store.saveEngineFinal(recording, differentId, transcript("Чужой результат")).owned());

        store.save(recording, transcript("Импорт пользователя"));
        assertFalse(store.saveEngineFinal(recording, id, transcript("Готовый текст")).owned());
        assertEquals("Импорт пользователя", store.load(recording).lines.get(0).text);
    }

    @Test public void summaryIsReusedOnlyForTheSameTranscript() throws Exception {
        var store = new ReflectTranscriptStore(context.getFilesDir());
        var original = transcript("Встреча состоится в пятницу");
        String fingerprint = ReflectSummaryController.sourceOf(original).fingerprint();
        store.save(recording, original);
        assertTrue(store.saveSummary(recording, fingerprint, "Договорились встретиться в пятницу."));

        var reopened = new ReflectTranscriptStore(context.getFilesDir());
        assertEquals("Договорились встретиться в пятницу.", reopened.loadSummary(recording, fingerprint));
        var replacement = transcript("Встречу перенесли на понедельник");
        reopened.save(recording, replacement);
        assertNull(reopened.loadSummary(recording, ReflectSummaryController.sourceOf(replacement).fingerprint()));
        assertFalse(reopened.saveSummary(recording, fingerprint, "Устаревший результат"));
        assertEquals("Встречу перенесли на понедельник", reopened.load(recording).lines.get(0).text);
    }

    private static ReflectTranscript transcript(String text) {
        return new ReflectTranscript(Collections.singletonList(new ReflectTranscript.Line("speaker_1", 0, 1000, text)));
    }
}
