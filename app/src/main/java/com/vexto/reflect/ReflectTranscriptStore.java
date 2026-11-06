package com.vexto.reflect;

import com.vexto.engine.model.EmotionProbabilities;
import java.io.DataOutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Private sidecar files keyed by the saved recording filename. */
final class ReflectTranscriptStore {
    private static final int MAX_TITLE_LENGTH = 200;
    private static final Object TRANSCRIPT_WRITE_LOCK = new Object();
    private static final CopyOnWriteArrayList<Consumer<File>> LISTENERS = new CopyOnWriteArrayList<>();
    private final File filesDirectory;

    static final class Snapshot {
        final ReflectTranscript transcript;
        final String revision;
        final long sourceLength;
        final long sourceModified;

        Snapshot(ReflectTranscript transcript, String revision, long sourceLength, long sourceModified) {
            this.transcript = transcript;
            this.revision = revision;
            this.sourceLength = sourceLength;
            this.sourceModified = sourceModified;
        }
    }

    static void addListener(Consumer<File> listener) { LISTENERS.addIfAbsent(listener); }
    static void removeListener(Consumer<File> listener) { LISTENERS.remove(listener); }

    private static void changed(File recording) {
        for (Consumer<File> listener : LISTENERS) {
            try {
                listener.accept(recording);
            } catch (RuntimeException failure) {
                android.util.Log.w("ReflectTranscriptStore", "Transcript change listener failed", failure);
            }
        }
    }

    Snapshot snapshot(File recording) throws IOException {
        synchronized (TRANSCRIPT_WRITE_LOCK) {
            ReflectTranscript transcript = load(recording);
            return transcript == null ? null : new Snapshot(transcript, revision(transcript),
                    recording.length(), recording.lastModified());
        }
    }

    /** Hash only ordered base fields: an emotion patch must not invalidate the queue cursor. */
    private static String revision(ReflectTranscript transcript) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(
                    new java.io.OutputStream() { @Override public void write(int value) {} }, digest))) {
                out.writeInt(transcript.lines.size());
                for (ReflectTranscript.Line line : transcript.lines) {
                    byte[] speaker = line.speakerId.getBytes(StandardCharsets.UTF_8);
                    byte[] text = line.text.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(speaker.length);
                    out.write(speaker);
                    out.writeInt(line.startMs);
                    out.writeInt(line.endMs);
                    out.writeInt(text.length);
                    out.write(text);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Compare-and-merge one prediction without overwriting an import or a manual score. */
    boolean applyEmotion(File recording, String expectedRevision, long expectedLength,
            long expectedModified, int lineIndex, EmotionProbabilities scores) throws IOException {
        if (scores == null) throw new IllegalArgumentException("Missing emotion scores");
        synchronized (TRANSCRIPT_WRITE_LOCK) {
            if (!recording.isFile() || recording.length() != expectedLength
                    || recording.lastModified() != expectedModified) return false;
            ReflectTranscript current = load(recording);
            if (current == null || !revision(current).equals(expectedRevision)
                    || lineIndex < 0 || lineIndex >= current.lines.size()) return false;
            ReflectTranscript.Line line = current.lines.get(lineIndex);
            if (line.emotionProbabilities != null) return true;
            ArrayList<ReflectTranscript.Line> lines = new ArrayList<>(current.lines);
            lines.set(lineIndex, new ReflectTranscript.Line(line.speakerId, line.startMs,
                    line.endMs, line.text, scores));
            writeAtomically(sidecar(recording, ".json"), new ReflectTranscript(lines).encode());
        }
        changed(recording);
        return true;
    }

    ReflectTranscriptStore(File filesDirectory) {
        this.filesDirectory = filesDirectory;
    }

    ReflectTranscript load(File recording) throws IOException {
        File path = sidecar(recording, ".json");
        if (!path.exists()) return null;
        try (FileInputStream input = new FileInputStream(path)) {
            return ReflectTranscript.parse(input);
        }
    }

    void save(File recording, ReflectTranscript transcript) throws IOException {
        File path = sidecar(recording, ".json");
        byte[] data = transcript.encode();
        synchronized (TRANSCRIPT_WRITE_LOCK) {
            writeAtomically(path, data);
        }
        changed(recording);
    }

    boolean saveIfAbsent(File recording, ReflectTranscript transcript) throws IOException {
        File path = sidecar(recording, ".json");
        byte[] data = transcript.encode();
        synchronized (TRANSCRIPT_WRITE_LOCK) {
            if (path.exists()) return false;
            writeAtomically(path, data);
        }
        changed(recording);
        return true;
    }

    String title(File recording, String fallback) {
        try {
            File path = sidecar(recording, ".title");
            try (Reader input = new InputStreamReader(new FileInputStream(path),
                    StandardCharsets.UTF_8.newDecoder())) {
                char[] chars = new char[MAX_TITLE_LENGTH + 1];
                int size = 0;
                while (size < chars.length) {
                    int count = input.read(chars, size, chars.length - size);
                    if (count < 0) break;
                    size += count;
                }
                return ReflectTranscript.checkedText(new String(chars, 0, size), MAX_TITLE_LENGTH);
            }
        } catch (IOException | IllegalArgumentException unavailable) {
            return fallback;
        }
    }

    void rename(File recording, String title) throws IOException {
        String value;
        try {
            value = ReflectTranscript.checkedText(title, MAX_TITLE_LENGTH).trim();
        } catch (IllegalArgumentException invalidTitle) {
            throw new IOException("Invalid recording title", invalidTitle);
        }
        writeAtomically(sidecar(recording, ".title"), value.getBytes(StandardCharsets.UTF_8));
    }

    private File sidecar(File recording, String suffix) throws IOException {
        if (recording == null) throw new IOException("Missing saved recording");
        File root = filesDirectory.getCanonicalFile();
        File recordings = new File(root, "recordings");
        File source = recording.getCanonicalFile();
        if (!recordings.equals(source.getParentFile()) || !source.isFile()) {
            throw new IOException("Recording is outside the saved recordings directory");
        }
        File path = new File(new File(root, "transcripts"), source.getName() + suffix);
        if (!path.equals(path.getCanonicalFile())) throw new IOException("Invalid transcript storage path");
        return path;
    }

    private static void writeAtomically(File target, byte[] data) throws IOException {
        Path directory = target.getParentFile().toPath();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".reflect-", ".pending");
        try {
            try (FileOutputStream output = new FileOutputStream(temp.toFile())) {
                output.write(data);
                output.getFD().sync();
            }
            Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
}
