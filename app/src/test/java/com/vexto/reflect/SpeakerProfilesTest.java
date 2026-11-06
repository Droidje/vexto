package com.vexto.reflect;

import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;
import com.vexto.engine.model.Speaker;
import com.vexto.engine.model.VoiceEmbedding;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SpeakerProfilesTest {
    private Context context;
    private SpeakerProfiles profiles;
    private File first, second, third;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("speaker-profiles.db");
        File dir = new File(context.getFilesDir(),"recordings"); assertTrue(dir.isDirectory() || dir.mkdirs());
        first = new File(dir,"first.wav"); second = new File(dir,"second.wav"); third = new File(dir,"third.wav");
        for (File file : List.of(first,second,third)) Files.write(file.toPath(),new byte[]{1});
        profiles = new SpeakerProfiles(context);
    }
    @After public void close() { profiles.close(); context.deleteDatabase("speaker-profiles.db"); }
    private static ReflectTranscript text(String words) {
        return new ReflectTranscript(List.of(new ReflectTranscript.Line("speaker_1",0,3000,words)));
    }
    private static Speaker voice(String model, float x, float y, long ms) {
        return new Speaker("speaker_1",null,new VoiceEmbedding(model,List.of(x,y),ms,3));
    }

    @Test public void profileAndLinksSurviveRestartAndRenameAcrossFiles() throws Exception {
        var transcript = text("Полный текст"); var profile = profiles.create("Алексей");
        profiles.link(first,transcript,"speaker_1",profile.id());
        profiles.link(second,transcript,"speaker_1",profile.id());
        profiles.close(); profiles = new SpeakerProfiles(context);
        assertEquals(profile.id(),profiles.list().get(0).id());
        profiles.rename(profile.id(),"Саша");
        assertEquals("Саша",profiles.names(first,transcript).get("speaker_1"));
        assertEquals("Саша",profiles.names(second,transcript).get("speaker_1"));
        assertEquals("Полный текст",transcript.lines.get(0).text);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RecordingExport.MARKDOWN.write(out,first,transcript,"Разговор",profiles.names(first,transcript));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Саша"));
        assertFalse(new String(transcript.encode(),StandardCharsets.UTF_8).contains("Саша"));
    }

    @Test public void localSpeakerIdsNeverActAsGlobalIdsAndRevisionsAreIsolated() throws Exception {
        var original = text("Первая версия"); var revised = text("Другая версия");
        var alice = profiles.create("Алиса"); var bob = profiles.create("Борис");
        profiles.link(first,original,"speaker_1",alice.id()); profiles.link(second,original,"speaker_1",bob.id());
        assertEquals("Алиса",profiles.names(first,original).get("speaker_1"));
        assertEquals("Борис",profiles.names(second,original).get("speaker_1"));
        assertTrue(profiles.links(first,revised).isEmpty());
        assertEquals(alice.id(),profiles.links(first,original).get("speaker_1"));
    }

    @Test public void badForeignKeyRollsBackAndDeleteOnlyDetachesProfiles() throws Exception {
        var transcript = text("Текст"); var alice = profiles.create("Алиса");
        profiles.link(first,transcript,"speaker_1",alice.id());
        try { profiles.link(first,transcript,"speaker_1","missing-uuid"); fail(); }
        catch (SQLiteConstraintException expected) {}
        assertEquals(alice.id(),profiles.links(first,transcript).get("speaker_1"));
        profiles.delete(alice.id()); assertTrue(profiles.links(first,transcript).isEmpty()); assertTrue(first.exists());
    }

    @Test public void onlyConfirmedSameModelOtherRecordingSamplesSuggestAProfile() throws Exception {
        var transcript = text("Текст"); var profile = profiles.create("Знакомый голос");
        profiles.saveEmbeddings(first,transcript,List.of(voice("model-A",1,0,3000)));
        profiles.saveEmbeddings(second,transcript,List.of(voice("model-A",1,0,3000)));
        assertTrue(profiles.suggestions(second,transcript,"speaker_1").isEmpty()); // no enrollment yet
        profiles.link(first,transcript,"speaker_1",profile.id());
        profiles.close(); profiles = new SpeakerProfiles(context);
        var matches = profiles.suggestions(second,transcript,"speaker_1");
        assertEquals(1,matches.size()); assertEquals(profile.id(),matches.get(0).profile().id());
        assertEquals(1,matches.get(0).score(),1e-6);
        assertTrue(profiles.links(second,transcript).isEmpty()); // never auto-assigned
        assertTrue(profiles.suggestions(first,transcript,"speaker_1").isEmpty()); // self match excluded
        profiles.saveEmbeddings(third,transcript,List.of(voice("model-B",1,0,3000)));
        assertTrue(profiles.suggestions(third,transcript,"speaker_1").isEmpty()); // same dimensions, different model
    }

    @Test public void shortAndInvalidVoiceSamplesCannotPolluteEnrollment() throws Exception {
        var transcript = text("Текст"); var profile = profiles.create("Голос");
        profiles.link(first,transcript,"speaker_1",profile.id());
        profiles.saveEmbeddings(first,transcript,List.of(voice("m",1,0,1000)));
        profiles.saveEmbeddings(second,transcript,List.of(voice("m",1,0,3000)));
        assertTrue(profiles.suggestions(second,transcript,"speaker_1").isEmpty());
        profiles.saveEmbeddings(first,transcript,List.of(voice("m",1,0,3000)));
        try { profiles.saveEmbeddings(first,transcript,List.of(voice("m",0,0,3000))); fail(); }
        catch (IllegalArgumentException expected) {}
        assertEquals(1,profiles.suggestions(second,transcript,"speaker_1").size());
        profiles.separate(first,transcript,"speaker_1");
        assertTrue(profiles.suggestions(second,transcript,"speaker_1").stream()
                .noneMatch(candidate -> candidate.profile().id().equals(profile.id())));
    }

    @Test public void rejectsRecordingOutsidePrivateDirectoryAndUnknownLocalSpeaker() throws Exception {
        var transcript = text("Текст"); var profile = profiles.create("Голос");
        File external = new File(context.getCacheDir(),"external.wav"); Files.write(external.toPath(),new byte[]{1});
        try { profiles.link(external,transcript,"speaker_1",profile.id()); fail(); } catch (java.io.IOException expected) {}
        try { profiles.link(first,transcript,"speaker_2",profile.id()); fail(); } catch (IllegalArgumentException expected) {}
        assertTrue(profiles.links(first,transcript).isEmpty());
    }

    @Test public void apkDoesNotRequestNetworkAccess() throws Exception {
        var info = context.getPackageManager().getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS);
        var permissions = info.requestedPermissions == null ? List.<String>of() : List.of(info.requestedPermissions);
        assertFalse(permissions.contains("android.permission.INTERNET"));
        assertFalse(permissions.contains("android.permission.ACCESS_NETWORK_STATE"));
    }
}
