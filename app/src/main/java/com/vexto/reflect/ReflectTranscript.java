package com.vexto.reflect;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import com.vexto.engine.model.EmotionProbabilities;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Chronological speaker segments in the portable transcript JSON format. */
final class ReflectTranscript {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LINES = 20_000;
    private static final int MAX_SPEAKER_LENGTH = 128;
    private static final int MAX_TEXT_LENGTH = 65_536;
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    static final class Line {
        final String speakerId;
        final int startMs;
        final int endMs;
        final String text;
        final String speakerName;
        final EmotionProbabilities emotionProbabilities;

        Line(String speakerId, int startMs, int endMs, String text) {
            this(speakerId, startMs, endMs, text, null);
        }

        Line(String speakerId, int startMs, int endMs, String text, EmotionProbabilities emotionProbabilities) {
            this(speakerId, startMs, endMs, text, emotionProbabilities, null);
        }

        Line(String speakerId, int startMs, int endMs, String text, EmotionProbabilities emotionProbabilities,
             String speakerName) {
            this.speakerId = checkedText(speakerId, MAX_SPEAKER_LENGTH);
            if (startMs < 0 || endMs <= startMs) {
                throw new IllegalArgumentException("Invalid transcript time range");
            }
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = checkedText(text, MAX_TEXT_LENGTH);
            this.emotionProbabilities = emotionProbabilities;
            this.speakerName = speakerName == null ? null : checkedText(speakerName, MAX_SPEAKER_LENGTH);
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

    boolean sameLines(ReflectTranscript other) {
        if (other == null || lines.size() != other.lines.size()) return false;
        for (int i = 0; i < lines.size(); i++) {
            Line first = lines.get(i), second = other.lines.get(i);
            if (first.startMs != second.startMs || first.endMs != second.endMs
                    || !first.speakerId.equals(second.speakerId) || !first.text.equals(second.text)
                    || !Objects.equals(first.speakerName, second.speakerName)
                    || !Objects.equals(first.emotionProbabilities, second.emotionProbabilities)) return false;
        }
        return true;
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
        String speaker = null, text = null, speakerName = null;
        int start = -1, end = -1;
        EmotionProbabilities emotions = null;
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
                case "speaker_name":
                    if (speakerName != null) throw new IOException("Duplicate speaker_name");
                    speakerName = readString(json);
                    break;
                case "emotion_probabilities":
                    if (emotions != null) throw new IOException("Duplicate emotion_probabilities");
                    json.beginArray();
                    emotions = new EmotionProbabilities(readProbability(json), readProbability(json),
                            readProbability(json), readProbability(json));
                    json.endArray();
                    break;
                default:
                    throw new IOException("Unknown transcript line field");
            }
        }
        json.endObject();
        if (speaker == null || text == null || start < 0 || end < 0) {
            throw new IOException("Incomplete transcript line");
        }
        return new Line(speaker, start, end, text, emotions, speakerName);
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

    private static double readProbability(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.NUMBER) throw new IOException("Expected an emotion probability");
        String number = json.nextString();
        if (!JSON_NUMBER.matcher(number).matches()) throw new IOException("Invalid emotion probability");
        return Double.parseDouble(number);
    }

    byte[] encode() throws IOException {
        return encode(true);
    }

    byte[] encodeSpeakerRevision() throws IOException {
        return encode(false);
    }

    private byte[] encode(boolean includeDetails) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeJson(output, Collections.emptyMap(), false, output, includeDetails);
        return output.toByteArray();
    }

    /** Export every line, even when presentation whitespace/names exceed the import budget. */
    void writeExport(OutputStream output, Map<String, String> names) throws IOException {
        writeJson(output, names, true, null, true);
    }

    private void writeJson(OutputStream output, Map<String, String> names, boolean pretty,
                           ByteArrayOutputStream bounded, boolean includeDetails) throws IOException {
        JsonWriter json = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        if (pretty) json.setIndent("  ");
        json.beginObject().name("transcript").beginArray();
        for (Line line : lines) {
            json.beginObject()
                    .name("speaker_id").value(line.speakerId)
                    .name("start_ms").value(line.startMs)
                    .name("end_ms").value(line.endMs)
                    .name("text").value(line.text);
            String name = names.getOrDefault(line.speakerId, line.speakerName);
            if (includeDetails && name != null) json.name("speaker_name").value(checkedText(name, MAX_SPEAKER_LENGTH));
            if (includeDetails && line.emotionProbabilities != null) {
                json.name("emotion_probabilities").beginArray();
                for (Double probability : line.emotionProbabilities.ordered()) json.value(probability);
                json.endArray();
            }
            json.endObject();
            if (bounded != null) {
                json.flush();
                if (bounded.size() > MAX_BYTES - 2) throw new IOException("Transcript is too large");
            }
        }
        json.endArray().endObject();
        json.flush();
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
            } else if ("{}[]:,-+.eE0123456789 \t\r\n".indexOf(c) < 0) {
                throw new IOException("Unexpected character in transcript JSON");
            }
        }
    }
}
