package com.vexto.reflect;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import androidx.core.content.FileProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Export previews and files use the same immutable transcript snapshot and speaker names. */
final class ReflectExportController {
    private final Activity activity;
    private final ReflectView view;
    private final ExecutorService io;
    private final SpeakerProfiles profiles;
    private final Consumer<String> notice;
    private File recording;
    private ReflectTranscript transcript;
    private RecordingMetadata metadata;
    private Map<String, String> names = Collections.emptyMap();
    private String title, length;
    private RecordingExport selected = RecordingExport.JSON;
    private boolean preparing, writing, ready;
    private int generation, previewRequest;

    ReflectExportController(Activity activity, ReflectView view, ExecutorService io,
                            SpeakerProfiles profiles, Consumer<String> notice) {
        this.activity = activity;
        this.view = view;
        this.io = io;
        this.profiles = profiles;
        this.notice = notice;
    }

    void open(File recording, ReflectTranscript transcript, RecordingMetadata metadata, String title,
              String length, RecordingExport initial) {
        int current = ++generation;
        this.recording = recording;
        this.transcript = transcript;
        this.metadata = metadata;
        this.title = title;
        this.length = length;
        selected = hasTranscript() ? initial : RecordingExport.AUDIO;
        preparing = true;
        ready = false;
        writing = false;
        names = Collections.emptyMap();
        render("Подготовка предпросмотра…");
        view.show(ReflectView.Screen.EXPORT);
        io.execute(() -> {
            try {
                Map<String, String> resolved = transcript == null
                        ? Collections.emptyMap() : profiles.exportNames(recording, transcript);
                deliver(current, () -> {
                    names = resolved;
                    ready = true;
                    preview();
                });
            } catch (IOException | RuntimeException failure) {
                deliver(current, () -> {
                    preparing = false;
                    render("Не удалось подготовить данные. Вернитесь к записи и откройте экспорт снова.");
                });
            }
        });
    }

    void select(RecordingExport format) {
        if (!ready || writing || (format != RecordingExport.AUDIO && !hasTranscript())) return;
        selected = format;
        preview();
    }

    void close() {
        ++generation;
        view.show(ReflectView.Screen.TRANSCRIPT);
    }

    void saveState(Bundle state) { state.putString("exportFormat", selected.name()); }

    static RecordingExport restoredFormat(Bundle state) {
        try { return RecordingExport.valueOf(state.getString("exportFormat", "JSON")); }
        catch (IllegalArgumentException invalid) { return RecordingExport.JSON; }
    }

    private boolean hasTranscript() { return transcript != null && !transcript.lines.isEmpty(); }

    private void render(String preview) {
        view.exportView().content(title, length, transcript, selected, preview,
                hasTranscript(), recording != null && ready && !preparing && !writing,
                ready && !writing, writing);
    }

    private void preview() {
        int current = generation, request = ++previewRequest;
        RecordingExport format = selected;
        ReflectTranscript source = transcript;
        RecordingMetadata details = metadata;
        Map<String, String> labels = names;
        String heading = title;
        preparing = true;
        render("Подготовка предпросмотра…");
        io.execute(() -> {
            String text;
            try {
                if (format == RecordingExport.AUDIO) {
                    text = details == null ? "Не удалось прочитать сведения об аудиофайле."
                            : heading + "\n\n" + details.format() + " · "
                            + Formatter.formatShortFileSize(activity, details.bytes())
                            + "\n\nИсходный аудиофайл без перекодирования.";
                } else text = previewText(format, source, heading, labels);
            } catch (IOException | RuntimeException error) {
                text = "Не удалось построить предпросмотр.";
            }
            String result = text;
            deliver(current, () -> {
                if (request != previewRequest) return;
                preparing = false;
                render(result);
            });
        });
    }

    static String previewText(RecordingExport format, ReflectTranscript source, String title,
                              Map<String, String> names) throws IOException {
        int count = Math.min(30, source.lines.size());
        ReflectTranscript sample = new ReflectTranscript(source.lines.subList(0, count));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        format.write(out, null, sample, title, names);
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        boolean truncated = count < source.lines.size() || text.length() > 12000;
        if (text.length() > 12000) text = text.substring(0, 12000);
        return text + (truncated ? "\n\n… Предпросмотр сокращён. В файле будут все реплики." : "");
    }

    void save() {
        if (recording == null || !ready || preparing || writing) return;
        activity.startActivityForResult(selected.createDocument(metadata, title), selected.request);
    }

    void share() {
        if (recording == null || !ready || preparing || writing) return;
        int current = generation;
        RecordingExport format = selected;
        File audio = recording;
        ReflectTranscript source = transcript;
        Map<String, String> labels = names;
        RecordingMetadata details = metadata;
        String heading = title;
        writing = true;
        render("Подготовка файла…");
        io.execute(() -> {
            try {
                File shared = audio;
                if (format != RecordingExport.AUDIO) {
                    File root = new File(activity.getCacheDir(), "exports");
                    Files.createDirectories(root.toPath());
                    File directory = Files.createTempDirectory(root.toPath(), "share-").toFile();
                    shared = new File(directory, format.filename(details, heading));
                    try (OutputStream out = Files.newOutputStream(shared.toPath())) {
                        format.write(out, audio, source, heading, labels);
                    }
                }
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".recordings", shared);
                Intent send = new Intent(Intent.ACTION_SEND).setType(format.mime(details))
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri(heading, uri));
                deliver(current, () -> {
                    writing = false;
                    preview();
                    try { activity.startActivity(Intent.createChooser(send, "Поделиться файлом")); }
                    catch (RuntimeException unavailable) { notice.accept("Не удалось открыть меню отправки файла"); }
                });
            } catch (IOException | RuntimeException failure) {
                deliver(current, () -> {
                    writing = false;
                    preview();
                    notice.accept("Не удалось экспортировать файл");
                });
            }
        });
    }

    private void deliver(int current, Runnable update) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed() && current == generation) update.run();
        });
    }
}
