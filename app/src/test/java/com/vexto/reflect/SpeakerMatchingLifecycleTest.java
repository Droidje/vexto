package com.vexto.reflect;

import android.content.Context;
import com.vexto.engine.model.Speaker;
import com.vexto.engine.model.VoiceEmbedding;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Regression: a directory refresh may assign a placeholder before voice inference finishes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SpeakerMatchingLifecycleTest {
    private Context context;
    private SpeakerProfiles profiles;
    private File oldRecording, newRecording;
    private final ReflectTranscript text = transcript("Реплика");

    private static ReflectTranscript transcript(String words) {
        return new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1", 0, 6000, words)));
    }

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("speaker-profiles.db");
        profiles = new SpeakerProfiles(context);
        File dir = new File(context.getFilesDir(), "recordings");
        Files.createDirectories(dir.toPath());
        oldRecording = Files.write(new File(dir, "old.wav").toPath(), new byte[]{1}).toFile();
        newRecording = Files.write(new File(dir, "new.wav").toPath(), new byte[]{1}).toFile();
    }

    @After public void close() { profiles.close(); context.deleteDatabase("speaker-profiles.db"); }

    private void saveVoice(File file, ReflectTranscript transcript) throws Exception {
        profiles.saveEmbeddings(file, transcript, List.of(new Speaker("speaker_1", null,
                new VoiceEmbedding(SpeakerIdentityMatcher.PROFILE_MODEL, List.of(1f, 0f), 6000, 12))));
    }

    private String enroll() throws Exception {
        saveVoice(oldRecording, text);
        return profiles.assign(oldRecording, text).get("speaker_1");
    }

    @Test public void lateVoiceReplacesOnlyUnconfirmedPlaceholderAfterRestart() throws Exception {
        String known = enroll();
        String placeholder = profiles.assign(newRecording, text).get("speaker_1");
        assertNotEquals(known, placeholder);
        profiles.close(); profiles = new SpeakerProfiles(context);
        saveVoice(newRecording, text);
        assertEquals(known, profiles.assign(newRecording, text).get("speaker_1"));
        assertEquals(1, profiles.visibleProfiles().size());
        assertEquals(known, profiles.assign(newRecording, text).get("speaker_1"));
    }

    @Test public void refinementRetainsRetryEligibilityAndExcludesOwnOldSignature() throws Exception {
        String known = enroll();
        profiles.assign(newRecording, text);
        var finalText = transcript("Уточнённая реплика");
        profiles.retainAcrossRefinement(newRecording, text, finalText);
        saveVoice(newRecording, finalText);
        assertEquals(known, profiles.assign(newRecording, finalText).get("speaker_1"));
    }

    @Test public void textOnlyRecheckKeepsTheKnownVoiceInTheActiveGallery() throws Exception {
        String known = enroll();
        profiles.rename(known, "Анна");
        profiles.markSelf(known);
        var corrected = transcript("Исправленная расшифровка без повторного извлечения голоса");
        profiles.retainAcrossRefinement(oldRecording, text, corrected);
        assertEquals(known, profiles.assign(oldRecording, corrected).get("speaker_1"));
        // Reopening the app must not depend on an obsolete revision remaining in memory.
        profiles.close(); profiles = new SpeakerProfiles(context);
        saveVoice(newRecording, text);
        assertEquals(known, profiles.assign(newRecording, text).get("speaker_1"));
        assertEquals("Анна", profiles.get(known).name());
        assertTrue(profiles.get(known).self());
        assertFalse(profiles.get(known).automatic());
    }

    @Test public void explicitSplitCannotBeUndoneByLateVoice() throws Exception {
        String known = enroll();
        profiles.assign(newRecording, text);
        profiles.separate(newRecording, text, "speaker_1");
        String separate = profiles.links(newRecording, text).get("speaker_1");
        saveVoice(newRecording, text);
        assertNotEquals(known, separate);
        assertEquals(separate, profiles.assign(newRecording, text).get("speaker_1"));
    }

    @Test public void manualLinkAndRenamedPlaceholderCannotBeReassigned() throws Exception {
        enroll();
        String placeholder = profiles.assign(newRecording, text).get("speaker_1");
        profiles.rename(placeholder, "Подтверждённый человек");
        saveVoice(newRecording, text);
        assertEquals(placeholder, profiles.assign(newRecording, text).get("speaker_1"));
        profiles.link(newRecording, text, "speaker_1", placeholder);
        var finalText = transcript("Финал");
        profiles.retainAcrossRefinement(newRecording, text, finalText);
        saveVoice(newRecording, finalText);
        assertEquals(placeholder, profiles.assign(newRecording, finalText).get("speaker_1"));
    }

    @Test public void legacyV2LinksRemainLockedWhenTheirOriginIsUnknown() throws Exception {
        profiles.close();
        try (var db = context.openOrCreateDatabase("speaker-profiles.db", Context.MODE_PRIVATE, null)) {
            db.execSQL("CREATE TABLE profiles(id TEXT PRIMARY KEY,name TEXT NOT NULL,is_self INTEGER DEFAULT 0,automatic INTEGER DEFAULT 0)");
            db.execSQL("CREATE TABLE links(recording TEXT,revision TEXT,local_id TEXT,profile_id TEXT,PRIMARY KEY(recording,revision,local_id))");
            db.execSQL("CREATE TABLE embeddings(recording TEXT,revision TEXT,local_id TEXT,model_id TEXT,dimension INTEGER,vector BLOB,"
                    + "speech_ms INTEGER,windows INTEGER,created_at INTEGER,PRIMARY KEY(recording,revision,local_id,model_id))");
            db.execSQL("CREATE TABLE active_transcripts(recording TEXT PRIMARY KEY,revision TEXT)");
            db.execSQL("INSERT INTO profiles VALUES('legacy','Спикер 1',0,1)");
            db.execSQL("INSERT INTO links VALUES(?,?,?,'legacy')", new Object[]{
                    newRecording.getName(), SpeakerProfiles.revision(text), "speaker_1"});
            db.setVersion(2);
        }
        profiles = new SpeakerProfiles(context);
        enroll();
        saveVoice(newRecording, text);
        assertEquals("legacy", profiles.assign(newRecording, text).get("speaker_1"));
        assertEquals(3, profiles.getReadableDatabase().getVersion());
    }

    @Test public void manualMergeAndSelfDesignationProtectPendingLinks() throws Exception {
        enroll();
        String pending = profiles.assign(newRecording, text).get("speaker_1");
        profiles.markSelf(pending);
        saveVoice(newRecording, text);
        assertEquals(pending, profiles.assign(newRecording, text).get("speaker_1"));
        var target = profiles.create("Общий");
        profiles.merge(pending, target.id());
        assertEquals(target.id(), profiles.assign(newRecording, text).get("speaker_1"));
        assertTrue(profiles.get(target.id()).self());
    }
}
