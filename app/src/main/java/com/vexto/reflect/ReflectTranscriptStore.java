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
    private static final int MAX_SUMMARY_BYTES = 64 * 1024;
    private static final Object TRANSCRIPT_WRITE_LOCK = new Object();
    private final File filesDirectory;

    ReflectTranscriptStore(File filesDirectory) {
        this.filesDirectory = filesDirectory;
    }

    ReflectTranscript load(File recording) throws IOException {
        File path = sidecar(recording, ".json");
        return readTranscript(path);
    }

    String loadSummary(File recording, String fingerprint) throws IOException {
        File file = sidecar(recording, ".summary");
        if (!file.isFile() || file.length() > MAX_SUMMARY_BYTES) return null;
        String value = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String prefix = "v1:" + fingerprint + "\n";
        return value.startsWith(prefix) && !value.substring(prefix.length()).trim().isEmpty()
                ? value.substring(prefix.length()) : null;
    }

    boolean saveSummary(File recording, String fingerprint, String summary) throws IOException {
        if (summary == null || summary.trim().isEmpty()) return false;
        byte[] data = ("v1:" + fingerprint + "\n" + summary.trim()).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_SUMMARY_BYTES) return false;
        synchronized (TRANSCRIPT_WRITE_LOCK) {
            ReflectTranscript current = load(recording);
            if (current == null || !ReflectSummaryController.sourceOf(current).fingerprint().equals(fingerprint)) return false;
            writeAtomically(sidecar(recording, ".summary"), data);
            return true;
        }
    }

    void save(File recording, ReflectTranscript transcript) throws IOException {
        File path = sidecar(recording, ".json");
        byte[] data = transcript.encode();
        synchronized (TRANSCRIPT_WRITE_LOCK) {
            writeAtomically(path, data);
        }
    }

    record EngineSave(ReflectTranscript transcript, ReflectTranscript provisional, boolean owned) {}

    /** Claim a core before publishing it, so the same job can refine it after process death. */
    boolean saveEngineCore(File recording, String jobId, ReflectTranscript transcript) throws IOException {
        File target = sidecar(recording, ".json");
        File marker = engineMarker(jobId, "published-core.json");
        synchronized (TRANSCRIPT_WRITE_LOCK) {
            if (target.exists()) return false;
            byte[] data = transcript.encode();
            writeAtomically(marker, data);
            writeAtomically(target, data);
            return true;
        }
    }

    /** Only an empty slot or the job's unchanged core can receive the finished transcript. */
    EngineSave saveEngineFinal(File recording, String jobId, ReflectTranscript transcript) throws IOException {
        File target = sidecar(recording, ".json");
        File marker = engineMarker(jobId, "published-final.json");
        synchronized (TRANSCRIPT_WRITE_LOCK) {
            ReflectTranscript existing = readTranscript(target);
            ReflectTranscript core = readTranscript(engineMarker(jobId, "published-core.json"));
            ReflectTranscript finalWritten = readTranscript(marker);
            if (finalWritten != null && finalWritten.sameLines(existing)) {
                return new EngineSave(existing, core, true);
            }
            if (existing != null && (core == null || !core.sameLines(existing))) {
                return new EngineSave(existing, null, false);
            }
            byte[] data = transcript.encode();
            writeAtomically(marker, data);
            writeAtomically(target, data);
            return new EngineSave(transcript, core, true);
        }
    }

    private static ReflectTranscript readTranscript(File path) throws IOException {
        if (!path.exists()) return null;
        try (FileInputStream input = new FileInputStream(path)) {
            return ReflectTranscript.parse(input);
        }
    }

    private File engineMarker(String jobId, String name) throws IOException {
        File root = new File(filesDirectory, "inference/jobs").getCanonicalFile();
        File job = new File(root, jobId);
        File marker = new File(job, name);
        if (!root.equals(job.getCanonicalFile().getParentFile()) || !marker.equals(marker.getCanonicalFile())) {
            throw new IOException("Invalid engine job storage path");
        }
        return marker;
    }

    void saveEngineArtifacts(File recording, String directory) throws IOException {
        File path = new File(directory).getCanonicalFile();
        File root = new File(filesDirectory, "inference/jobs").getCanonicalFile();
        if (!root.equals(path.getParentFile())) throw new IOException("Engine artifacts outside private job storage");
        writeAtomically(sidecar(recording, ".engine"), path.getName().getBytes(StandardCharsets.UTF_8));
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
