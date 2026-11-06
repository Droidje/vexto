package com.vexto.reflect;

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
    private final File filesDirectory;

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
        writeAtomically(path, transcript.encode());
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
