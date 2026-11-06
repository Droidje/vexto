package com.vexto.reflect;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.vexto.engine.model.EmotionProbabilities;
import com.vexto.engine.model.Speaker;
import com.vexto.engine.model.VoiceEmbedding;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.HexFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SpeakerDirectoryTest {
    private Context context;
    private SpeakerProfiles profiles;
    private SpeakerDirectory directory;
    private ReflectTranscriptStore store;
    private File first, second, third;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("speaker-profiles.db");
        profiles = new SpeakerProfiles(context);
        store = new ReflectTranscriptStore(context.getFilesDir());
        directory = new SpeakerDirectory(context.getFilesDir(), store, profiles);
        File recordings = new File(context.getFilesDir(), "recordings");
        Files.createDirectories(recordings.toPath());
        first = file(recordings, "01.wav");
        second = file(recordings, "02.wav");
        third = file(recordings, "03.wav");
    }

    @After public void close() { profiles.close(); context.deleteDatabase("speaker-profiles.db"); }

    private static File file(File parent, String name) throws Exception {
        return Files.write(new File(parent, name).toPath(), new byte[]{1}).toFile();
    }

    private static ReflectTranscript transcript(String text) {
        return new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1", 0, 6000, text)));
    }

    private static Speaker voice(float first, float second) {
        return new Speaker("speaker_1", null, new VoiceEmbedding(SpeakerIdentityMatcher.PROFILE_MODEL,
                List.of(first, second), 6000, 12));
    }

    @Test public void globalVoiceMatchAndManualSplitPersistAcrossDirectoryRefreshes() throws Exception {
        var one = transcript("Первый диалог");
        var two = transcript("Другой текст");
        var three = transcript("Без голосового образца");
        store.save(first, one); store.save(second, two); store.save(third, three);
        profiles.saveEmbeddings(first, one, List.of(voice(1, 0)));
        profiles.saveEmbeddings(second, two, List.of(voice(.98f, .1f)));

        assertEquals(2, directory.list().size());
        String shared = profiles.links(first, one).get("speaker_1");
        assertEquals(shared, profiles.links(second, two).get("speaker_1"));
        assertNotEquals(shared, profiles.links(third, three).get("speaker_1"));
        var detail = directory.detail(shared);
        assertEquals(2, detail.conversations());
        assertEquals(12_000, detail.speechMs());
        assertEquals(2, detail.utterances().size());

        profiles.separate(second, two, "speaker_1");
        String split = profiles.links(second, two).get("speaker_1");
        assertNotEquals(shared, split);
        assertEquals(3, directory.list().size());
        assertEquals(split, profiles.links(second, two).get("speaker_1"));
    }

    @Test public void mergeMovesAllLinesKeepsTargetNameAndTransfersSoleSelfFlag() throws Exception {
        var one = new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1", 0, 5000, "Раз"),
                new ReflectTranscript.Line("speaker_2", 3000, 6000, "Два")));
        var two = transcript("Три");
        store.save(first, one); store.save(second, two);
        var source = profiles.create("Исходный");
        var target = profiles.create("Общий");
        var other = profiles.create("Другой");
        profiles.link(first, one, "speaker_1", source.id());
        profiles.link(first, one, "speaker_2", target.id());
        profiles.link(second, two, "speaker_1", source.id());
        profiles.markSelf(other.id());
        profiles.markSelf(source.id());
        assertFalse(profiles.get(other.id()).self());
        profiles.merge(source.id(), target.id());

        var detail = directory.detail(target.id());
        assertEquals("Общий", detail.profile().name());
        assertTrue(detail.profile().self());
        assertEquals(1, profiles.list().stream().filter(SpeakerProfiles.Profile::self).count());
        assertEquals(2, detail.conversations());
        assertEquals(12_000, detail.speechMs());
        assertEquals(3, detail.utterances().size());
        assertFalse(profiles.list().stream().anyMatch(p -> p.id().equals(source.id())));
        assertArrayEquals(one.encode(), store.load(first).encode());
        assertArrayEquals(two.encode(), store.load(second).encode());
    }

    @Test public void supersededVoiceDoesNotEnrollAnotherRecording() throws Exception {
        var old = transcript("Старая версия");
        store.save(first, old);
        profiles.saveEmbeddings(first, old, List.of(voice(1, 0)));
        directory.list();
        String outdated = profiles.links(first, old).get("speaker_1");
        var changed = transcript("Полностью другой импорт");
        store.save(first, changed);
        var secondText = transcript("Новый разговор");
        store.save(second, secondText);
        profiles.saveEmbeddings(second, secondText, List.of(voice(1, 0)));
        assertEquals(2, directory.list().size());
        assertNotEquals(outdated, profiles.links(second, secondText).get("speaker_1"));
        assertNotEquals(profiles.links(first, changed).get("speaker_1"), profiles.links(second, secondText).get("speaker_1"));
    }

    @Test public void migratesLegacyLinksAndKeepsIdentityWhenEmotionScoresChange() throws Exception {
        var base = transcript("Один и тот же текст");
        var emotional = new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1", 0, 6000,
                "Один и тот же текст", new EmotionProbabilities(.4, .3, .2, .1))));
        store.save(first, emotional);
        String legacyRevision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(emotional.encode()));
        try (SQLiteDatabase legacy = context.openOrCreateDatabase("speaker-profiles.db", Context.MODE_PRIVATE, null)) {
            legacy.execSQL("CREATE TABLE profiles(id TEXT PRIMARY KEY,name TEXT NOT NULL)");
            legacy.execSQL("CREATE TABLE links(recording TEXT, revision TEXT, local_id TEXT, profile_id TEXT, "
                    + "PRIMARY KEY(recording,revision,local_id))");
            legacy.execSQL("CREATE TABLE embeddings(recording TEXT, revision TEXT, local_id TEXT, model_id TEXT, "
                    + "dimension INTEGER, vector BLOB, speech_ms INTEGER, windows INTEGER, created_at INTEGER, "
                    + "PRIMARY KEY(recording,revision,local_id,model_id))");
            legacy.execSQL("INSERT INTO profiles VALUES ('legacy-uuid','Подтверждённый')");
            legacy.execSQL("INSERT INTO links VALUES (?,?,?,?)",
                    new Object[]{first.getName(), legacyRevision, "speaker_1", "legacy-uuid"});
            legacy.setVersion(1);
        }
        assertEquals(1, directory.list().size());
        assertEquals("legacy-uuid", profiles.links(first, emotional).get("speaker_1"));
        assertEquals(SpeakerProfiles.revision(base), SpeakerProfiles.revision(emotional));
        store.save(first, base);
        assertEquals("legacy-uuid", profiles.assign(first, base).get("speaker_1"));
    }
}
