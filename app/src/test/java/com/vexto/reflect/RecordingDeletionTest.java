package com.vexto.reflect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecordingDeletionTest {
    private Context context;
    private ReflectConversationStore conversations;
    private SpeakerProfiles profiles;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("conversations.db");
        context.deleteDatabase("speaker-profiles.db");
        conversations = new ReflectConversationStore(context);
        profiles = new SpeakerProfiles(context);
        Files.createDirectories(new File(context.getFilesDir(), "recordings").toPath());
    }

    @After public void cleanup() {
        profiles.close();
        conversations.close();
        context.deleteDatabase("conversations.db");
        context.deleteDatabase("speaker-profiles.db");
    }

    @Test public void deletesOnlySelectedAudioAndDerivedDataAndKeepsPeople() throws Exception {
        File selected = recording("selected.wav");
        File other = recording("other.wav");
        var store = new ReflectTranscriptStore(context.getFilesDir());
        var transcript = new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1", 0, 1000, "Текст")));
        store.save(selected, transcript);
        store.save(other, transcript);
        String person = profiles.create("Анна").id();
        profiles.link(selected, transcript, "speaker_1", person);
        profiles.link(other, transcript, "speaker_1", person);
        profiles.assign(selected, transcript);
        profiles.assign(other, transcript);
        store.rename(selected, "Удаляемая запись");
        store.saveSummary(selected, ReflectSummaryController.sourceOf(transcript).fingerprint(), "Краткое содержание.");
        String jobId = UUID.randomUUID().toString();
        File job = new File(context.getFilesDir(), "inference/jobs/" + jobId);
        Files.createDirectories(job.toPath());
        Files.write(new File(job, "original.wav").toPath(), new byte[]{3});
        store.saveEngineArtifacts(selected, job.getPath());
        new ReflectTranscriptionOutcomes(context).finish(selected, false, "Неудача");
        conversations.title(selected);
        conversations.title(other);

        var deletion = new RecordingDeletion(context, conversations, profiles);
        deletion.delete(selected);
        deletion.recover();

        assertFalse(selected.exists());
        assertTrue(other.isFile());
        assertNotNull(store.load(other));
        assertFalse(new File(context.getFilesDir(), "transcripts/selected.wav.json").exists());
        assertFalse(new File(context.getFilesDir(), "transcripts/selected.wav.summary").exists());
        assertFalse(new File(context.getFilesDir(), "transcripts/selected.wav.title").exists());
        assertFalse(new File(context.getFilesDir(), "transcripts/selected.wav.engine").exists());
        assertFalse(job.exists());
        assertEquals(null, new ReflectTranscriptionOutcomes(context).failure(selected));
        assertTrue(conversations.isDeleted(selected));
        assertFalse(conversations.isDeleted(other));
        assertEquals("Анна", profiles.get(person).name());
        assertEquals(person, profiles.links(other, transcript).get("speaker_1"));
        for (String table : new String[]{"links", "embeddings", "active_transcripts"}) {
            try (var rows = profiles.getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + table + " WHERE recording=?", new String[]{selected.getName()})) {
                assertTrue(rows.moveToFirst());
                assertEquals(0, rows.getInt(0));
            }
        }
    }

    @Test public void recoveryCompletesACommittedIntentEvenWhenTheAudioAlreadyDisappeared() throws Exception {
        File selected = recording("interrupted.wav");
        var transcript = new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1", 0, 1000, "Текст")));
        new ReflectTranscriptStore(context.getFilesDir()).save(selected, transcript);
        conversations.title(selected);
        File folder = new File(context.getFilesDir(), "recording-deletions");
        Files.createDirectories(folder.toPath());
        // AtomicFile can leave a previous valid plan in its backup after process death.
        Files.write(new File(folder, selected.getName() + ".json.bak").toPath(),
                new JSONObject().put("recording", selected.getName()).toString().getBytes(StandardCharsets.UTF_8));
        Files.delete(selected.toPath());

        new RecordingDeletion(context, conversations, profiles).recover();
        assertTrue(conversations.isDeleted(selected));
        assertFalse(new File(context.getFilesDir(), "transcripts/interrupted.wav.json").exists());
        assertFalse(new File(folder, selected.getName() + ".json").exists());
        assertFalse(new File(folder, selected.getName() + ".json.bak").exists());
    }

    @Test public void rejectsAFileOutsideTheRecordingLibrary() throws Exception {
        File outside = new File(context.getCacheDir(), "outside.wav");
        Files.write(outside.toPath(), new byte[]{1});
        assertThrows(java.io.IOException.class,
                () -> new RecordingDeletion(context, conversations, profiles).delete(outside));
        assertTrue(outside.isFile());
    }

    @Test public void preservesAnEngineJobReferencedByAnotherRecording() throws Exception {
        File selected = recording("selected.wav"), other = recording("other.wav");
        String jobId = UUID.randomUUID().toString();
        File job = new File(context.getFilesDir(), "inference/jobs/" + jobId);
        Files.createDirectories(job.toPath());
        Files.write(new File(job, "original.wav").toPath(), new byte[]{3});
        var store = new ReflectTranscriptStore(context.getFilesDir());
        store.saveEngineArtifacts(selected, job.getPath());
        store.saveEngineArtifacts(other, job.getPath());

        new RecordingDeletion(context, conversations, profiles).delete(selected);

        assertTrue(job.isDirectory());
        assertTrue(new File(context.getFilesDir(), "transcripts/other.wav.engine").isFile());
    }

    @Test public void refusesToDeleteAPersistedPendingJob() throws Exception {
        File selected = recording("processing.wav");
        PendingTranscription pending = PendingTranscription.create(context, selected);
        pending.write(context);
        try {
            assertThrows(java.io.IOException.class,
                    () -> new RecordingDeletion(context, conversations, profiles).delete(selected));
            assertTrue(selected.isFile());
            assertFalse(conversations.isDeleted(selected));
        } finally {
            PendingTranscription.clear(context);
        }
    }

    private File recording(String name) throws Exception {
        File recording = new File(context.getFilesDir(), "recordings/" + name);
        Files.write(recording.toPath(), new byte[]{1, 2});
        return recording;
    }
}
