package com.vexto.reflect;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private static final DateTimeFormatter DATE_WITH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", RUSSIAN);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM", RUSSIAN);
    private static final DateTimeFormatter SHORT_DATE_WITH_YEAR = DateTimeFormatter.ofPattern("dd.MM.yy", RUSSIAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", RUSSIAN);

    static final class Recording {
        final File file;
        final String title, date, timeLabel, length, preview, search;
        final List<String> participants;

        Recording(File file, String title, String date, String timeLabel, String length, String preview,
                  List<String> participants) {
            this.file = file;
            this.title = title;
            this.date = date;
            this.timeLabel = timeLabel;
            this.length = length;
            this.preview = preview;
            this.participants = participants;
            this.search = title + " " + String.join(" ", participants);
        }

        boolean hasTranscript() { return preview != null; }
    }

    static final class Speaker {
        final String id, name, duration, avatar;
        final boolean self;

        Speaker(String id, String name, String duration, String avatar, boolean self) {
            this.id = id;
            this.name = name;
            this.duration = duration;
            this.avatar = avatar;
            this.self = self;
        }

        String displayName() { return speakerName(name, self); }
    }

    static final class Conversation {
        final String title, preview, search;
        final Recording recording;
        final boolean needsTitle;

        Conversation(ReflectConversationStore.Entry entry, String preview, Recording recording) {
            this.title = entry == null ? "Разговор" : entry.title();
            this.preview = preview;
            this.recording = recording;
            needsTitle = entry != null && entry.needsTitle() && recording.hasTranscript();
            this.search = title + " " + recording.search
                    + (entry == null ? "" : " " + ReflectConversationStore.numberedTitle(entry.number()));
        }
    }

    private ReflectCatalog() {}

    static String speakerName(String name, boolean self) { return self ? "Вы" : name; }

    static String timeLabel(ZonedDateTime recorded, LocalDate today) {
        LocalDate day = recorded.toLocalDate();
        String time = TIME.format(recorded);
        if (day.equals(today)) return time;
        DateTimeFormatter format = day.getYear() == today.getYear() ? SHORT_DATE : SHORT_DATE_WITH_YEAR;
        return format.format(day) + " · " + time;
    }

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
            DateTimeFormatter headingFormat = day.getYear() == today.getYear() ? DATE : DATE_WITH_YEAR;
            String date = day.equals(today) ? "Сегодня" : headingFormat.format(day);
            int seconds = duration / 1000;
            String length = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
            String title = transcripts.title(file, "Запись №" + (files.length - i));
            ReflectTranscript transcript = loadTranscript(transcripts, file);
            String preview = transcript == null ? null : preview(transcript.lines.get(0).text);
            List<String> participants = transcript == null ? List.of()
                    : participants(profiles, file, transcript, profileNames);
            recordings.add(new Recording(file, title, date, timeLabel(modified, today), length, preview, participants));
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
        Map<String, ReflectConversationStore.Entry> entries;
        try { entries = store.assign(recordings); }
        catch (RuntimeException unavailable) { entries = Map.of(); }
        List<Conversation> conversations = new ArrayList<>(recordings.size());
        for (Recording recording : recordings) {
            var entry = entries.get(recording.file.getName());
            if (entry != null && entry.deleted()) continue;
            conversations.add(new Conversation(entry,
                    recording.hasTranscript() ? recording.preview : "Локальная аудиозапись · " + recording.length,
                    recording));
        }
        return Collections.unmodifiableList(conversations);
    }

    record Quote(Conversation conversation, int startMs, String excerpt) {}

    static List<Conversation> searchConversations(List<Conversation> conversations, String query) {
        List<Conversation> results = new ArrayList<>();
        for (Conversation conversation : conversations) {
            if (matches(normalized(conversation.search), normalized(query))) results.add(conversation);
        }
        return results;
    }

    static List<Quote> searchConversationQuotes(List<Conversation> conversations, String query,
                                                ReflectTranscriptStore transcripts) {
        List<Quote> results = new ArrayList<>();
        String[] terms = normalized(query).trim().split("\\s+");
        for (Conversation conversation : conversations) {
            if (Thread.currentThread().isInterrupted() || results.size() >= 100) break;
            List<String> remaining = new ArrayList<>(Arrays.asList(terms));
            remaining.removeIf(normalized(conversation.search)::contains);
            ReflectTranscript transcript = loadTranscript(transcripts, conversation.recording.file);
            if (transcript == null) continue;
            for (ReflectTranscript.Line line : transcript.lines) {
                if (Thread.currentThread().isInterrupted()) return results;
                String text = com.vexto.engine.model.PhoneticSurface.present(line.text);
                String searchable = normalized(text);
                if (!remaining.stream().allMatch(searchable::contains)) continue;
                int match = remaining.stream().mapToInt(searchable::indexOf).filter(index -> index >= 0)
                        .min().orElse(0);
                results.add(new Quote(conversation, line.startMs, excerpt(text, match)));
                if (results.size() >= 100) break;
            }
        }
        return results;
    }

    private static String excerpt(String text, int match) {
        int start = Math.max(0, match - 40);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
        int end = Math.min(text.length(), start + 220);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return (start == 0 ? "" : "…") + text.substring(start, end).trim()
                + (end == text.length() ? "" : "…");
    }

    private static String normalized(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
