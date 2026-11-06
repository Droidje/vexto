package com.vexto.reflect;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Chronological speaker segments in the portable transcript JSON format. */
final class ReflectTranscript {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LINES = 20_000;
    private static final int MAX_SPEAKER_LENGTH = 128;
    private static final int MAX_TEXT_LENGTH = 65_536;

    static final class Line {
        final String speakerId;
        final int startMs;
        final int endMs;
        final String text;

        Line(String speakerId, int startMs, int endMs, String text) {
            this.speakerId = checkedText(speakerId, MAX_SPEAKER_LENGTH);
            if (startMs < 0 || endMs <= startMs) {
                throw new IllegalArgumentException("Invalid transcript time range");
            }
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = checkedText(text, MAX_TEXT_LENGTH);
        }
    }

    final List<Line> lines;

    ReflectTranscript(List<Line> lines) {
        if (lines.size() > MAX_LINES) throw new IllegalArgumentException("Too many transcript lines");
        List<Line> ordered = new ArrayList<>(lines);
        for (Line line : ordered) Objects.requireNonNull(line);
        ordered.sort(Comparator.comparingInt(line -> line.startMs));
        this.lines = Collections.unmodifiableList(ordered);
    }

    /** The caller owns the stream. */
    static ReflectTranscript parse(InputStream input) throws IOException {
        Reader utf8 = new InputStreamReader(new LimitedInput(input), StandardCharsets.UTF_8.newDecoder());
        JsonReader json = new JsonReader(new TranscriptJsonReader(utf8));
        List<Line> lines = new ArrayList<>();
        try {
            json.beginObject();
            if (!json.hasNext() || !"transcript".equals(json.nextName())) {
                throw new IOException("Missing transcript array");
            }
            json.beginArray();
            while (json.hasNext()) {
                if (lines.size() == MAX_LINES) throw new IOException("Too many transcript lines");
                lines.add(readLine(json));
            }
            json.endArray();
            json.endObject();
            if (json.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected trailing data");
            return new ReflectTranscript(lines);
        } catch (IllegalStateException | IllegalArgumentException malformed) {
            throw new IOException("Invalid transcript JSON", malformed);
        }
    }

    private static Line readLine(JsonReader json) throws IOException {
        String speaker = null, text = null;
        int start = -1, end = -1;
        json.beginObject();
        while (json.hasNext()) {
            String field = json.nextName();
            switch (field) {
                case "speaker_id":
                    if (speaker != null) throw new IOException("Duplicate speaker_id");
                    speaker = readString(json);
                    break;
                case "start_ms":
                    if (start != -1) throw new IOException("Duplicate start_ms");
                    start = readTime(json);
                    break;
                case "end_ms":
                    if (end != -1) throw new IOException("Duplicate end_ms");
                    end = readTime(json);
                    break;
                case "text":
                    if (text != null) throw new IOException("Duplicate text");
                    text = readString(json);
                    break;
                default:
                    throw new IOException("Unknown transcript line field");
            }
        }
        json.endObject();
        if (speaker == null || text == null || start < 0 || end < 0) {
            throw new IOException("Incomplete transcript line");
        }
        return new Line(speaker, start, end, text);
    }

    private static String readString(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.STRING) throw new IOException("Expected a JSON string");
        return json.nextString();
    }

    private static int readTime(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.NUMBER) throw new IOException("Expected an integer timestamp");
        String token = json.nextString();
        if (token.charAt(0) == '-' || (token.length() > 1 && token.charAt(0) == '0')) {
            throw new IOException("Invalid transcript timestamp");
        }
        return Integer.parseInt(token);
    }

    byte[] encode() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonWriter json = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            json.beginObject().name("transcript").beginArray();
            for (Line line : lines) {
                json.beginObject()
                        .name("speaker_id").value(line.speakerId)
                        .name("start_ms").value(line.startMs)
                        .name("end_ms").value(line.endMs)
                        .name("text").value(line.text)
                        .endObject();
                json.flush();
                if (output.size() > MAX_BYTES - 2) throw new IOException("Transcript is too large");
            }
            json.endArray().endObject();
        }
        return output.toByteArray();
    }

    static String checkedText(String value, int maxLength) {
        if (value == null || value.length() > maxLength || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid transcript text length");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Invalid Unicode in transcript text");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Invalid Unicode in transcript text");
            }
        }
        return value;
    }

    private static final class LimitedInput extends FilterInputStream {
        private int bytes;

        LimitedInput(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, Math.min(length, MAX_BYTES - bytes + 1));
            if (count > 0) account(count);
            return count;
        }

        private void account(int count) throws IOException {
            bytes += count;
            if (bytes > MAX_BYTES) throw new IOException("Transcript is too large");
        }
    }

    /**
     * Android's JsonReader accepts some unescaped control characters, separators and
     * string escapes in strict mode. Check those lexically before it reads the schema.
     */
    private static final class TranscriptJsonReader extends FilterReader {
        private boolean inString;
        private boolean escaped;
        private int hexDigits;

        TranscriptJsonReader(Reader reader) { super(reader); }

        @Override public int read(char[] chars, int offset, int length) throws IOException {
            int count = in.read(chars, offset, length);
            for (int i = 0; i < count; i++) validate(chars[offset + i]);
            return count;
        }

        private void validate(char c) throws IOException {
            if (hexDigits > 0) {
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    throw new IOException("Invalid JSON Unicode escape");
                }
                hexDigits--;
            } else if (escaped) {
                escaped = false;
                if (c == 'u') hexDigits = 4;
                else if ("\"\\/bfnrt".indexOf(c) < 0) throw new IOException("Invalid JSON string escape");
            } else if (c == '"') {
                inString = !inString;
            } else if (inString) {
                if (c < 0x20) throw new IOException("Unescaped control character in JSON");
                if (c == '\\') escaped = true;
            } else if ("{}[]:,-0123456789 \t\r\n".indexOf(c) < 0) {
                throw new IOException("Unexpected character in transcript JSON");
            }
        }
    }
}
