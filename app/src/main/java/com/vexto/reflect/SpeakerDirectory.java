package com.vexto.reflect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the directory from active local transcript sidecars. Text stays in its original recording. */
final class SpeakerDirectory {
    record Summary(SpeakerProfiles.Profile profile, int conversations, int utterances, long speechMs) {}
    record Detail(SpeakerProfiles.Profile profile, int conversations, long speechMs, List<Utterance> utterances) {}
    record Utterance(File recording, String title, int startMs, int endMs, String text) {}

    private final File recordings;
    private final ReflectTranscriptStore store;
    private final SpeakerProfiles profiles;

    SpeakerDirectory(File filesDir, ReflectTranscriptStore store, SpeakerProfiles profiles) {
        recordings = new File(filesDir, "recordings");
        this.store = store;
        this.profiles = profiles;
    }

    List<Summary> list() throws IOException {
        Map<String, Stats> statistics = new HashMap<>();
        visit((recording, title, transcript, links) -> {
            for (var line : transcript.lines) {
                String id = links.get(line.speakerId);
                if (id != null) statistics.computeIfAbsent(id, unused -> new Stats()).add(recording, line);
            }
        });
        List<Summary> result = new ArrayList<>();
        for (var profile : profiles.visibleProfiles()) {
            Stats stats = statistics.get(profile.id());
            result.add(stats == null ? new Summary(profile, 0, 0, 0)
                    : new Summary(profile, stats.conversations, stats.utterances, stats.speechMs));
        }
        return List.copyOf(result);
    }

    Detail detail(String id) throws IOException {
        var profile = profiles.get(id);
        Stats stats = new Stats();
        ArrayDeque<List<Utterance>> groups = new ArrayDeque<>();
        visit((recording, title, transcript, links) -> {
            List<Utterance> lines = new ArrayList<>();
            for (var line : transcript.lines) {
                if (!id.equals(links.get(line.speakerId))) continue;
                stats.add(recording, line);
                lines.add(new Utterance(recording, title, line.startMs, line.endMs, line.text));
            }
            if (!lines.isEmpty()) groups.addFirst(lines);
        });
        List<Utterance> utterances = new ArrayList<>(stats.utterances);
        for (var group : groups) utterances.addAll(group);
        return new Detail(profile, stats.conversations, stats.speechMs, List.copyOf(utterances));
    }

    private void visit(Visitor visitor) throws IOException {
        File[] files = recordings.listFiles(File::isFile);
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        Set<String> existing = new HashSet<>();
        for (File file : files) existing.add(file.getName());
        profiles.pruneActive(existing);
        // Oldest first gives the registry an earlier recording before it sees a later one.
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            ReflectTranscript transcript;
            try { transcript = store.load(file); }
            catch (IOException unreadable) { transcript = null; }
            if (transcript == null || transcript.lines.isEmpty()) {
                profiles.forgetActive(file.getName());
                continue;
            }
            Map<String, String> links = profiles.assign(file, transcript);
            visitor.accept(file, store.title(file, "Запись №" + (i + 1)), transcript, links);
        }
    }

    private interface Visitor {
        void accept(File recording, String title, ReflectTranscript transcript, Map<String, String> links);
    }

    private static final class Stats {
        File lastRecording;
        int conversations, utterances, lastEnd;
        long speechMs;

        void add(File recording, ReflectTranscript.Line line) {
            if (!recording.equals(lastRecording)) {
                lastRecording = recording;
                conversations++;
                lastEnd = 0;
            }
            utterances++;
            // Rows are ordered by start time, so overlapping speech counts once after a manual merge.
            speechMs += Math.max(0, line.endMs - Math.max(lastEnd, line.startMs));
            lastEnd = Math.max(lastEnd, line.endMs);
        }
    }
}
