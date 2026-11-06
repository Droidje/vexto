package com.vexto.reflect;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.vexto.engine.model.EmotionProbabilities;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses Android's actual SQLite and atomic sidecar files in an isolated test directory/database. */
@RunWith(AndroidJUnit4.class)
public class ReflectEmotionStorageTest {
    private Context context;
    private File root;
    private ReflectTranscriptStore store;
    private ReflectEmotionQueue queue;
    private String databaseName;
    private final EmotionProbabilities scores = new EmotionProbabilities(0.1, 0.2, 0.3, 0.4);

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        root = Files.createTempDirectory(context.getCacheDir().toPath(), "emotion-storage-").toFile();
        assertTrue(new File(root, "recordings").mkdir());
        store = new ReflectTranscriptStore(root);
        databaseName = "emotion-test-" + System.nanoTime() + ".db";
        queue = new ReflectEmotionQueue(context, databaseName);
    }

    @After public void tearDown() throws Exception {
        queue.close();
        context.deleteDatabase(databaseName);
        delete(root);
    }

    @Test public void casRejectsChangedTranscriptAndChangedSource() throws Exception {
        File recording = recording("first.wav");
        store.save(recording, transcript("old text"));
        ReflectTranscriptStore.Snapshot old = store.snapshot(recording);
        store.save(recording, transcript("imported text"));
        assertFalse(apply(recording, old, 0));
        assertEquals("imported text", store.load(recording).lines.get(0).text);
        ReflectTranscriptStore.Snapshot imported = store.snapshot(recording);
        Files.write(recording.toPath(), new byte[]{1, 2, 3, 4});
        assertFalse(apply(recording, imported, 0));
        assertNull(store.load(recording).lines.get(0).emotionProbabilities);
    }

    @Test public void mergePreservesOtherPredictionsAndAllBaseFields() throws Exception {
        File recording = recording("first.wav");
        store.save(recording, transcript("Полный исходный текст"));
        ReflectTranscriptStore.Snapshot snapshot = store.snapshot(recording);
        EmotionProbabilities manual = new EmotionProbabilities(0.7, 0.1, 0.1, 0.1);
        store.save(recording, new ReflectTranscript(Arrays.asList(
                snapshot.transcript.lines.get(0),
                new ReflectTranscript.Line("speaker_2", 100, 200, "Second", manual))));
        assertTrue(apply(recording, snapshot, 0));
        ReflectTranscriptStore.Snapshot merged = store.snapshot(recording);
        assertEquals(snapshot.revision, merged.revision);
        assertEquals("Полный исходный текст", merged.transcript.lines.get(0).text);
        assertEquals("speaker_1", merged.transcript.lines.get(0).speakerId);
        assertEquals(0, merged.transcript.lines.get(0).startMs);
        assertEquals(100, merged.transcript.lines.get(0).endMs);
        assertEquals(scores, merged.transcript.lines.get(0).emotionProbabilities);
        assertEquals(manual, merged.transcript.lines.get(1).emotionProbabilities);
        assertTrue(apply(recording, snapshot, 1));
        assertEquals(manual, store.load(recording).lines.get(1).emotionProbabilities);
    }

    @Test public void fifoCheckpointSurvivesReopenAndTombstoneStopsRescan() throws Exception {
        File first = recording("first.wav"), second = recording("second.wav");
        store.save(first, transcript("First"));
        store.save(second, transcript("Second"));
        queue.enqueue(first, store.snapshot(first));
        queue.enqueue(second, store.snapshot(second));
        ReflectEmotionQueue.Job job = queue.peek();
        assertEquals(first.getName(), job.recordingName);
        assertTrue(apply(first, store.snapshot(first), 0));
        queue.advance(job, 1);
        queue.close();
        queue = new ReflectEmotionQueue(context, databaseName);
        job = queue.peek();
        assertEquals(first.getName(), job.recordingName);
        assertEquals(1, job.nextIndex);
        queue.finish(job); // Remaining unsupported null line must not trigger another job on discovery.
        queue.enqueue(first, store.snapshot(first));
        assertEquals(second.getName(), queue.peek().recordingName);
        queue.finish(queue.peek());
        assertFalse(queue.hasPending());
        assertEquals(-1, queue.nextDelayMs());
        assertNull(queue.peek());
    }

    @Test public void retryLetsLaterRecordProceedAndYieldDoesNotConsumeAttempts() throws Exception {
        File first = recording("first.wav"), second = recording("second.wav");
        store.save(first, transcript("First"));
        store.save(second, transcript("Second"));
        queue.enqueue(first, store.snapshot(first));
        queue.enqueue(second, store.snapshot(second));
        ReflectEmotionQueue.Job firstJob = queue.peek();
        queue.fail(firstJob);
        assertEquals(second.getName(), queue.peek().recordingName);
        queue.finish(queue.peek());
        assertTrue(queue.hasPending());
        assertTrue(queue.nextDelayMs() > 0);
        assertNull(queue.peek());
        queue.close();
        queue = new ReflectEmotionQueue(context, databaseName);
        // Inspect the persisted retry state without waiting thirty seconds.
        try (android.database.sqlite.SQLiteDatabase db = context.openOrCreateDatabase(databaseName, 0, null)) {
            db.execSQL("UPDATE jobs SET ready_at=0 WHERE done=0");
        }
        ReflectEmotionQueue.Job retry = queue.peek();
        assertEquals(1, retry.attempts);
        queue.deferYield(retry);
        try (android.database.sqlite.SQLiteDatabase db = context.openOrCreateDatabase(databaseName, 0, null)) {
            db.execSQL("UPDATE jobs SET ready_at=0 WHERE done=0");
        }
        assertEquals(1, queue.peek().attempts);
        queue.advance(queue.peek(), 1);
        assertEquals(0, queue.peek().attempts);
    }

    @Test public void replacementJoinsTailAndOldJobCannotMutateIt() throws Exception {
        File first = recording("first.wav"), second = recording("second.wav");
        store.save(first, transcript("First"));
        store.save(second, transcript("Second"));
        queue.enqueue(first, store.snapshot(first));
        ReflectEmotionQueue.Job old = queue.peek();
        queue.enqueue(second, store.snapshot(second));
        store.save(first, transcript("Replacement import"));
        queue.enqueue(first, store.snapshot(first));
        queue.advance(old, 1);
        queue.finish(old);
        assertEquals(second.getName(), queue.peek().recordingName);
        queue.finish(queue.peek());
        ReflectEmotionQueue.Job replacement = queue.peek();
        assertEquals(first.getName(), replacement.recordingName);
        assertNotEquals(old.id, replacement.id);
        assertEquals(0, replacement.nextIndex);
        assertEquals(store.snapshot(first).revision, replacement.revision);
        assertTrue(first.delete());
        queue.pruneMissing(new File(root, "recordings"));
        assertFalse(queue.hasPending());
    }

    @Test public void listenersObserveCommittedWritesAndAbsentSaveDoesNotNotify() throws Exception {
        File recording = recording("first.wav");
        AtomicInteger calls = new AtomicInteger();
        Consumer<File> listener = file -> {
            if (!file.equals(recording)) return;
            try { assertNotNull(store.load(file)); }
            catch (Exception failure) { throw new AssertionError(failure); }
            calls.incrementAndGet();
        };
        ReflectTranscriptStore.addListener(listener);
        try {
            assertTrue(store.saveIfAbsent(recording, transcript("First")));
            assertFalse(store.saveIfAbsent(recording, transcript("Ignored")));
            store.save(recording, transcript("Updated"));
            assertTrue(apply(recording, store.snapshot(recording), 0));
            assertEquals(3, calls.get());
            assertEquals("none", store.load(recording).lines.get(1).emotionName());
            assertNotEquals("none", store.load(recording).lines.get(0).emotionName());
        } finally { ReflectTranscriptStore.removeListener(listener); }
    }

    private boolean apply(File recording, ReflectTranscriptStore.Snapshot snapshot, int index) throws Exception {
        return store.applyEmotion(recording, snapshot.revision, snapshot.sourceLength,
                snapshot.sourceModified, index, scores);
    }

    private File recording(String name) throws Exception {
        File file = new File(new File(root, "recordings"), name);
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        return file;
    }

    private ReflectTranscript transcript(String firstText) {
        return new ReflectTranscript(Arrays.asList(
                new ReflectTranscript.Line("speaker_1", 0, 100, firstText),
                new ReflectTranscript.Line("speaker_2", 100, 200, "Second")));
    }

    private static void delete(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(file.toPath());
    }
}
