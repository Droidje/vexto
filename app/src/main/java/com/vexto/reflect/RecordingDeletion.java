package com.vexto.reflect;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.LinkedHashSet;
import org.json.JSONException;
import org.json.JSONObject;

/** Resumable removal of one private recording and its derived data. Run on the app IO executor. */
final class RecordingDeletion {
    private static final String[] SIDECARS = {".json", ".summary", ".engine", ".title"};
    private static final Object LOCK = new Object();
    private final Context context;
    private final File files, recordings, transcripts, journal;
    private final ReflectConversationStore conversations;
    private final SpeakerProfiles profiles;
    private final ReflectTranscriptionOutcomes outcomes;

    RecordingDeletion(Context context, ReflectConversationStore conversations, SpeakerProfiles profiles) {
        this.context = context.getApplicationContext();
        files = context.getFilesDir();
        recordings = new File(files, "recordings");
        transcripts = new File(files, "transcripts");
        journal = new File(files, "recording-deletions");
        this.conversations = conversations;
        this.profiles = profiles;
        outcomes = new ReflectTranscriptionOutcomes(context);
    }

    void delete(File recording) throws IOException {
        synchronized (LOCK) {
            String name = checkedName(recording.getName());
            File source = checkedSource(name);
            if (!source.equals(recording.getCanonicalFile()) || !source.isFile()) {
                throw new IOException("Expected an existing private recording");
            }
            ensureNotPending(name);
            if (!journal.mkdirs() && !journal.isDirectory()) throw new IOException("No deletion journal directory");
            File existing = marker(name);
            if (existing.exists() || new File(existing.getPath() + ".bak").exists()) {
                remove(read(existing));
            } else {
                Plan plan = new Plan(name, jobFor(name));
                write(plan);
                remove(plan);
            }
        }
    }

    /** Completes interrupted deletions before the catalog reads the recordings directory. */
    void recover() throws IOException {
        synchronized (LOCK) {
            File[] pending = journal.listFiles(file -> file.isFile()
                    && (file.getName().endsWith(".json") || file.getName().endsWith(".json.bak")));
            if (pending == null) return;
            var names = new LinkedHashSet<String>();
            for (File marker : pending) {
                String name = marker.getName();
                names.add(name.endsWith(".bak") ? name.substring(0, name.length() - 4) : name);
            }
            for (String marker : names) {
                Plan plan = read(new File(journal.getCanonicalFile(), marker));
                ensureNotPending(plan.recording());
                remove(plan);
            }
        }
    }

    private void ensureNotPending(String name) throws IOException {
        PendingTranscription pending = PendingTranscription.read(context);
        if (pending != null && pending.recording().getName().equals(name)) {
            throw new IOException("The recording is still being processed");
        }
    }

    private void remove(Plan plan) throws IOException {
        File source = checkedSource(plan.recording());
        // A tombstone blocks late title callbacks and catalog reassignment after a partial failure.
        conversations.title(source);
        conversations.delete(source);
        profiles.forgetRecording(plan.recording());
        outcomes.clear(source);
        if (plan.job() != null && !sharedJob(plan)) removeJob(plan.job());
        for (String suffix : SIDECARS) {
            Files.deleteIfExists(new File(transcripts, plan.recording() + suffix).toPath());
        }
        Files.deleteIfExists(source.toPath());
        new AtomicFile(marker(plan.recording())).delete();
    }

    private String jobFor(String name) throws IOException {
        File marker = new File(transcripts, name + ".engine");
        if (!marker.isFile() || marker.length() > 80) return null;
        String value = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
        return validJob(value) ? value : null;
    }

    private boolean sharedJob(Plan plan) throws IOException {
        File[] pointers = transcripts.listFiles(file -> file.isFile() && file.getName().endsWith(".engine"));
        if (pointers == null) return false;
        for (File pointer : pointers) {
            if (pointer.getName().equals(plan.recording() + ".engine") || pointer.length() > 80) continue;
            String value = new String(Files.readAllBytes(pointer.toPath()), StandardCharsets.UTF_8).trim();
            if (plan.job().equals(value)) return true;
        }
        return false;
    }

    private void removeJob(String name) throws IOException {
        File root = new File(files, "inference/jobs").getCanonicalFile();
        File job = new File(root, name);
        if (!root.equals(job.getCanonicalFile().getParentFile()) || !job.equals(job.getCanonicalFile())) {
            throw new IOException("Invalid private job path");
        }
        if (!Files.exists(job.toPath())) return;
        // walkFileTree does not follow symbolic links.
        Files.walkFileTree(job.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private File checkedSource(String name) throws IOException {
        File root = recordings.getCanonicalFile();
        File source = new File(root, checkedName(name));
        if (!source.equals(source.getCanonicalFile())) throw new IOException("Invalid private recording path");
        return source;
    }

    private static String checkedName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || !name.equals(new File(name).getName())) {
            throw new IOException("Invalid recording name");
        }
        return name;
    }

    private File marker(String name) throws IOException {
        return new File(journal.getCanonicalFile(), checkedName(name) + ".json");
    }

    private void write(Plan plan) throws IOException {
        AtomicFile file = new AtomicFile(marker(plan.recording()));
        var output = file.startWrite();
        try {
            JSONObject data = new JSONObject().put("recording", plan.recording()).put("job", plan.job());
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException | JSONException | RuntimeException error) {
            file.failWrite(output);
            throw new IOException("Could not persist recording deletion", error);
        }
    }

    private Plan read(File marker) throws IOException {
        if (!marker.equals(marker.getCanonicalFile())) {
            throw new IOException("Invalid recording deletion journal");
        }
        try {
            byte[] bytes = new byte[4097];
            int size = 0;
            try (var input = new AtomicFile(marker).openRead()) {
                while (size < bytes.length) {
                    int count = input.read(bytes, size, bytes.length - size);
                    if (count < 0) break;
                    size += count;
                }
            }
            if (size > 4096) throw new IOException("Oversized recording deletion journal");
            JSONObject data = new JSONObject(new String(bytes, 0, size, StandardCharsets.UTF_8));
            String name = checkedName(data.getString("recording"));
            if (!marker.getName().equals(name + ".json")) throw new IOException("Mismatched recording deletion journal");
            String job = data.isNull("job") ? null : data.getString("job");
            if (job != null && !validJob(job)) throw new IOException("Invalid deletion job id");
            return new Plan(name, job);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Invalid recording deletion journal", error);
        }
    }

    private static boolean validJob(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private record Plan(String recording, String job) {}
}
