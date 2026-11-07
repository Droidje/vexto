package com.vexto.reflect;

import android.content.Context;
import android.util.AtomicFile;
import com.vexto.engine.model.ProcessingMode;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** The app's durable pointer to one saved recording and its engine checkpoint directory. */
record PendingTranscription(File recording, String jobId, ProcessingMode processingMode) {
    private static final int MAX_BYTES = 4096;

    PendingTranscription(File recording, String jobId) {
        this(recording, jobId, ProcessingMode.AUTO);
    }

    static PendingTranscription create(Context context, File recording) throws IOException {
        return new PendingTranscription(checkedRecording(context, recording.getName(), recording),
                UUID.randomUUID().toString(), new ReflectPreferences(context).processingMode());
    }

    static PendingTranscription read(Context context) throws IOException {
        AtomicFile file = file(context);
        try (var input = file.openRead()) {
            byte[] data = new byte[MAX_BYTES + 1];
            int length = 0;
            while (length < data.length) {
                int count = input.read(data, length, data.length - length);
                if (count < 0) break;
                length += count;
            }
            if (length > MAX_BYTES) throw new IOException("Invalid pending transcription size");
            JSONObject json = new JSONObject(new String(data, 0, length, StandardCharsets.UTF_8));
            String jobId = json.getString("jobId");
            if (!UUID.fromString(jobId).toString().equals(jobId)) throw new IOException("Invalid job id");
            ProcessingMode mode = ProcessingMode.valueOf(json.optString("processingMode", ProcessingMode.AUTO.name()));
            return new PendingTranscription(checkedRecording(context, json.getString("recording"), null), jobId, mode);
        } catch (FileNotFoundException absent) {
            if (file.getBaseFile().exists()) throw absent;
            return null;
        } catch (JSONException | IllegalArgumentException malformed) {
            throw new IOException("Invalid pending transcription", malformed);
        }
    }

    void write(Context context) throws IOException {
        AtomicFile file = file(context);
        FileOutputStream output = file.startWrite();
        try {
            JSONObject data = new JSONObject().put("recording", recording.getName()).put("jobId", jobId)
                    .put("processingMode", processingMode.name());
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException | JSONException | RuntimeException failure) {
            file.failWrite(output);
            throw new IOException("Cannot write pending transcription", failure);
        }
        if (!equals(read(context))) throw new IOException("Cannot commit pending transcription");
    }

    static void clear(Context context) throws IOException {
        AtomicFile file = file(context);
        file.delete();
        File base = file.getBaseFile();
        if (base.exists() || new File(base.getPath() + ".bak").exists()) {
            throw new IOException("Cannot clear pending transcription");
        }
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "pending-transcription.json"));
    }

    private static File checkedRecording(Context context, String name, File expected) throws IOException {
        if (name.isEmpty() || !name.equals(new File(name).getName())) throw new IOException("Invalid recording name");
        File recordings = new File(context.getFilesDir(), "recordings");
        File source = new File(recordings, name);
        File canonical = source.getCanonicalFile();
        if (!new File(recordings.getCanonicalFile(), name).equals(canonical) || !source.isFile()
                || (expected != null && !canonical.equals(expected.getCanonicalFile()))) {
            throw new IOException("Expected a saved local recording");
        }
        // Keep the same filesDir spelling used by the UI and the recording catalog.
        return source;
    }
}
