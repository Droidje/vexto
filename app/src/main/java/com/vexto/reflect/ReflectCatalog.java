package com.vexto.reflect;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ReflectCatalog {
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", RUSSIAN);

    static final class Recording {
        final File file;
        final String title, date, time, length, preview, search;
        final List<String> participants;

        Recording(File file, String title, String date, String time, String length, String preview,
                  List<String> participants) {
            this.file = file;
            this.title = title;
            this.date = date;
            this.time = time;
            this.length = length;
            this.preview = preview;
            this.participants = participants;
            this.search = title + " " + String.join(" ", participants);
        }

        boolean hasTranscript() { return preview != null; }
    }

    static final class Speaker {
        final String id, name, duration, avatar;

        Speaker(String id, String name, String duration, String avatar) {
            this.id = id;
            this.name = name;
            this.duration = duration;
            this.avatar = avatar;
        }
    }

    static final class Conversation {
        final String title, time, preview, search;
        final Recording recording;

        Conversation(String title, String time, String preview, Recording recording) {
            this.title = title;
            this.time = time;
            this.preview = preview;
            this.recording = recording;
            this.search = title + " " + recording.search;
        }
    }

    private ReflectCatalog() {}

    static List<Recording> localRecordings(File directory, ReflectTranscriptStore transcripts, SpeakerProfiles profiles) {
        File[] files = directory.listFiles(File::isFile);
        if (files == null) return Collections.emptyList();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed()
                .thenComparing(File::getName));
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<Recording> recordings = new ArrayList<>(files.length);
        Map<String, String> profileNames = new HashMap<>();
        try {
            for (var profile : profiles.list()) profileNames.put(profile.id(), profile.name());
        } catch (RuntimeException unavailable) {
            // A profile database failure must not hide playable recordings or their transcripts.
        }
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            int duration;
            try {
                duration = RecordingSession.duration(file);
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            var modified = Instant.ofEpochMilli(file.lastModified()).atZone(zone);
            LocalDate day = modified.toLocalDate();
            String date = day.equals(today) ? "Сегодня" : DATE.format(day);
            int seconds = duration / 1000;
            String length = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
            String title = transcripts.title(file, "Запись №" + (files.length - i));
            ReflectTranscript transcript = loadTranscript(transcripts, file);
            String preview = transcript == null ? null : preview(transcript.lines.get(0).text);
            List<String> participants = transcript == null ? List.of()
                    : participants(profiles, file, transcript, profileNames);
            recordings.add(new Recording(file, title, date, TIME.format(modified), length, preview, participants));
        }
        return Collections.unmodifiableList(recordings);
    }

    private static ReflectTranscript loadTranscript(ReflectTranscriptStore store, File recording) {
        try {
            ReflectTranscript transcript = store.load(recording);
            return transcript == null || transcript.lines.isEmpty() ? null : transcript;
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static String preview(String text) {
        String flattened = text.replaceAll("\\s+", " ").trim();
        int count = flattened.codePointCount(0, flattened.length());
        return count > 240 ? flattened.substring(0, flattened.offsetByCodePoints(0, 240)) + "…" : flattened;
    }

    private static List<String> participants(SpeakerProfiles profiles, File recording, ReflectTranscript transcript,
                                             Map<String, String> profileNames) {
        Map<String, String> links;
        try { links = profiles.links(recording, transcript); }
        catch (IOException | RuntimeException unavailable) { links = Map.of(); }

        Map<String, ReflectTranscript.Line> distinct = new LinkedHashMap<>();
        for (var line : transcript.lines) {
            String profile = links.get(line.speakerId);
            String key = profile == null ? "local:" + line.speakerId : "profile:" + profile;
            distinct.merge(key, line, (first, next) -> first.speakerName == null && next.speakerName != null
                    ? next : first);
        }
        List<String> result = new ArrayList<>(distinct.size());
        for (var line : distinct.values()) {
            String fallback = line.speakerName == null ? line.speakerId : line.speakerName;
            result.add(profileNames.getOrDefault(links.get(line.speakerId), fallback));
        }
        return List.copyOf(result);
    }

    static boolean matches(String fields, String query) {
        String normalized = fields.toLowerCase(Locale.ROOT);
        for (String term : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!normalized.contains(term)) return false;
        }
        return true;
    }

    static List<Conversation> localConversations(List<Recording> recordings, ReflectConversationStore store) {
        Map<String, Long> numbers;
        try { numbers = store.assign(recordings); }
        catch (RuntimeException unavailable) { numbers = Map.of(); }
        List<Conversation> conversations = new ArrayList<>(recordings.size());
        for (Recording recording : recordings) {
            conversations.add(new Conversation(ReflectConversationStore.title(numbers.get(recording.file.getName())), recording.time,
                    recording.hasTranscript() ? recording.preview : "Локальная аудиозапись · " + recording.length,
                    recording));
        }
        return Collections.unmodifiableList(conversations);
    }

    static List<Conversation> searchConversations(List<Conversation> conversations, String query,
                                                   ReflectTranscriptStore transcripts) {
        List<Conversation> results = new ArrayList<>();
        String[] terms = normalized(query).trim().split("\\s+");
        for (Conversation conversation : conversations) {
            if (Thread.currentThread().isInterrupted()) break;
            List<String> remaining = new ArrayList<>(Arrays.asList(terms));
            remaining.removeIf(normalized(conversation.search)::contains);
            if (!remaining.isEmpty() && conversation.recording.hasTranscript()) {
                ReflectTranscript transcript = loadTranscript(transcripts, conversation.recording.file);
                if (transcript != null) {
                    for (ReflectTranscript.Line line : transcript.lines) {
                        if (Thread.currentThread().isInterrupted()) return results;
                        remaining.removeIf(normalized(line.text)::contains);
                        if (remaining.isEmpty()) break;
                    }
                }
            }
            if (remaining.isEmpty()) results.add(conversation);
        }
        return results;
    }

    private static String normalized(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
