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
import java.util.List;
import java.util.Locale;

final class ReflectCatalog {
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", RUSSIAN);

    static final class Recording {
        final File file;
        final String title, date, time, length, status, search;
        final boolean complete, waveCover;
        final List<String> portraits;

        Recording(File file, String title, String date, String time, String length, String status,
                  boolean complete, boolean waveCover, List<String> portraits, String search) {
            this.file = file;
            this.title = title;
            this.date = date;
            this.time = time;
            this.length = length;
            this.status = status;
            this.complete = complete;
            this.waveCover = waveCover;
            this.portraits = portraits;
            this.search = title + " " + search;
        }
    }

    private ReflectCatalog() {}

    static List<Recording> localRecordings(File directory) {
        File[] files = directory.listFiles(File::isFile);
        if (files == null) return Collections.emptyList();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed()
                .thenComparing(File::getName));
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<Recording> recordings = new ArrayList<>(files.length);
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
            recordings.add(new Recording(file, "Запись №" + (files.length - i), date,
                    TIME.format(modified), length, "Сохранено", false, false,
                    Collections.emptyList(), ""));
        }
        return Collections.unmodifiableList(recordings);
    }

    static boolean matches(String fields, String query) {
        String normalized = fields.toLowerCase(Locale.ROOT);
        for (String term : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!normalized.contains(term)) return false;
        }
        return true;
    }
}
