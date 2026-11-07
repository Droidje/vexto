package com.vexto.reflect;

import android.content.Context;
import com.vexto.engine.model.Speaker;
import com.vexto.engine.model.VoiceEmbedding;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Optional real-vector replay through the actual SQLite matcher, not a Python reimplementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SpeakerMatchingReplayTest {
    private record Sample(File file, ReflectTranscript text, String person, String session, String local) {}

    @Test public void replayPublicEres2netVectorsWithLateEnrollment() throws Exception {
        String input = System.getenv("VEXTO_SPEAKER_AB_INPUT");
        String output = System.getenv("VEXTO_SPEAKER_AB_OUTPUT");
        assumeNotNull(input, output); // Explicit offline experiment; regular CI has no audio/model corpus.
        JSONObject data = new JSONObject(new String(Files.readAllBytes(new File(input).toPath()), StandardCharsets.UTF_8));
        assertEquals("eres2net", data.getString("model"));
        assertEquals("short-agc", data.getString("style"));
        assertEquals("sha256:" + SpeakerIdentityMatcher.PROFILE_MODEL.substring(
                SpeakerIdentityMatcher.PROFILE_MODEL.lastIndexOf(':') + 1), data.getString("model_revision"));
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("speaker-profiles.db");
        File dir = new File(context.getFilesDir(), "recordings");
        Files.createDirectories(dir.toPath());
        List<Sample> samples = new ArrayList<>();
        Map<String, List<JSONObject>> sessions = new TreeMap<>();
        JSONArray items = data.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.getJSONObject(i);
            if (!row.getString("split").equals("test")) continue;
            sessions.computeIfAbsent(row.getString("session_id"), unused -> new ArrayList<>()).add(row);
        }
        try (SpeakerProfiles profiles = new SpeakerProfiles(context)) {
            for (var session : sessions.entrySet()) {
                File file = Files.write(new File(dir, "sample-" + samples.size() + ".wav").toPath(), new byte[]{1}).toFile();
                List<Speaker> voices = new ArrayList<>();
                List<ReflectTranscript.Line> lines = new ArrayList<>();
                for (JSONObject row : session.getValue()) {
                    String local = row.getString("local_id");
                    lines.add(new ReflectTranscript.Line(local, lines.size() * 30000, (lines.size() + 1) * 30000, "Реплика"));
                    JSONArray vector = row.getJSONArray("embedding");
                    List<Float> values = new ArrayList<>();
                    for (int d = 0; d < vector.length(); d++) values.add((float) vector.getDouble(d));
                    voices.add(new Speaker(local, null, new VoiceEmbedding(SpeakerIdentityMatcher.PROFILE_MODEL,
                            values, Math.round(row.getDouble("speech_seconds") * 1000), row.getInt("window_count"))));
                }
                var text = new ReflectTranscript(lines);
                profiles.assign(file, text);
                profiles.saveEmbeddings(file, text, voices);
                profiles.assign(file, text);
                for (JSONObject row : session.getValue()) samples.add(new Sample(file, text,
                        row.getString("person_id"), session.getKey(), row.getString("local_id")));
            }
            int positive = 0, negative = 0, misses = 0, falseMerges = 0;
            for (int i = 0; i < samples.size(); i++) for (int j = i + 1; j < samples.size(); j++) {
                Sample a = samples.get(i), b = samples.get(j);
                if (a.session.equals(b.session)) continue;
                boolean same = a.person.equals(b.person);
                boolean linked = profiles.links(a.file, a.text).get(a.local)
                        .equals(profiles.links(b.file, b.text).get(b.local));
                if (same) { positive++; if (!linked) misses++; }
                else { negative++; if (linked) falseMerges++; }
            }
            JSONObject result = new JSONObject().put("profiles", samples.size()).put("positive_pairs", positive)
                    .put("missed_pairs", misses).put("negative_pairs", negative).put("false_merges", falseMerges)
                    .put("global_profiles", profiles.visibleProfiles().size()).put("style", data.getString("style"));
            Files.write(new File(output).toPath(), (result.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            assertTrue(positive > 0 && negative > 0);
        } finally { context.deleteDatabase("speaker-profiles.db"); }
    }
}
