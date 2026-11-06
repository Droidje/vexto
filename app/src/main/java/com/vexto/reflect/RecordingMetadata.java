package com.vexto.reflect;

import android.media.MediaMetadataRetriever;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Metadata read from the audio container, with file properties available for older recordings. */
record RecordingMetadata(Instant date, boolean embeddedDate, String location, long bytes,
                         String mime, String extension, String bitrate) {
    private static final DateTimeFormatter CONTAINER_DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss[.SSS]X", Locale.ROOT);
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("d MMMM uuuu, HH:mm", Locale.forLanguageTag("ru"));
    private static final Instant QUICKTIME_EPOCH = Instant.parse("1904-01-01T00:00:00Z");
    private static final Pattern COORDINATES = Pattern.compile(
            "([+-]\\d{1,2}(?:\\.\\d+)?)([+-]\\d{1,3}(?:\\.\\d+)?)(?:[+-]\\d+(?:\\.\\d+)?)?/?");

    static RecordingMetadata read(File file) {
        String date = null, location = null, mime = null, bitrate = null;
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try {
            try {
                media.setDataSource(file.getAbsolutePath());
                date = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
                location = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
                mime = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                bitrate = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            } finally {
                media.release();
            }
        } catch (IOException | RuntimeException unavailable) {
            // The details still include the properties of the saved file.
        }
        Instant embedded = parseDate(date);
        // Containers can report their epoch when the timestamp field was never set.
        if (QUICKTIME_EPOCH.equals(embedded) || Instant.EPOCH.equals(embedded)) embedded = null;
        long saved = file.lastModified();
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "audio";
        MimeTypeMap types = MimeTypeMap.getSingleton();
        String extensionType = types.getMimeTypeFromExtension(extension);
        if (extensionType != null && !"application/octet-stream".equals(extensionType)) {
            mime = extensionType;
        } else if (mime != null && mime.startsWith("audio/")) {
            String detected = "audio/mp4".equals(mime) ? "m4a" : types.getExtensionFromMimeType(mime);
            if (detected != null) extension = detected;
        } else {
            mime = extensionType;
        }
        return new RecordingMetadata(embedded != null ? embedded : saved > 0 ? Instant.ofEpochMilli(saved) : null,
                embedded != null, coordinates(location), file.length(), mime, extension, formatBitrate(bitrate));
    }

    String dateLabel() { return embeddedDate ? "Дата аудио" : "Дата сохранения"; }

    String dateText() {
        return date == null ? "Не указана" : DISPLAY_DATE.format(date.atZone(ZoneId.systemDefault()));
    }

    String format() {
        return "audio".equals(extension) ? "Не определён" : extension.toUpperCase(Locale.ROOT);
    }

    private static Instant parseDate(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.from(CONTAINER_DATE.parse(value));
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static String coordinates(String raw) {
        if (raw == null) return "Не указана";
        Matcher match = COORDINATES.matcher(raw.trim());
        if (!match.matches()) return "Не указана";
        double latitude = Double.parseDouble(match.group(1));
        double longitude = Double.parseDouble(match.group(2));
        if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return "Не указана";
        return String.format(Locale.ROOT, "%.4f°, %.4f°", latitude, longitude);
    }

    private static String formatBitrate(String raw) {
        if (raw == null) return null;
        try {
            long bits = Long.parseLong(raw);
            return bits > 0 ? Math.round(bits / 1000.0) + " кбит/с" : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
