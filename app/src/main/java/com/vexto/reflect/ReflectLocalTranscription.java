package com.vexto.reflect;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.vexto.engine.ReflectEngine;
import com.vexto.engine.model.ProcessingStats;
import com.vexto.engine.model.Stage;
import com.vexto.engine.model.Transcript;
import com.vexto.engine.model.TranscriptionOptions;
import com.vexto.engine.model.Utterance;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Coordinates one local inference task and commits only its complete transcript. */
final class ReflectLocalTranscription {
    record Progress(String stage, int percent, boolean cancellable) {}
    record Result(File recording, ReflectTranscript transcript, String message) {}

    private static final String SAVE_FAILED = "Не удалось сохранить расшифровку.";
    private final Context context;
    private final ReflectTranscriptStore store;
    private final ReflectBackendSync backend;
    private final Executor io;
    private final Consumer<File> changed;
    private final Consumer<Result> finished;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Job job;
    private boolean closed;

    ReflectLocalTranscription(Context context, ReflectTranscriptStore store, ReflectBackendSync backend,
                              Executor io, Consumer<File> changed, Consumer<Result> finished) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.backend = backend;
        this.io = io;
        this.changed = changed;
        this.finished = finished;
    }

    boolean busy() { return job != null; }

    Progress progress(File recording) {
        return job != null && job.recording.equals(recording) ? job.progress : null;
    }

    void start(File recording, String title, int duration) {
        if (closed || busy()) return;
        Job started = new Job(recording);
        String scope = backend.syncScope();
        job = started;
        changed.accept(recording);
        started.task = ReflectEngine.transcribe(context, Uri.fromFile(recording),
                new TranscriptionOptions(null, false), new ReflectEngine.Listener() {
            @Override public void onProgress(Stage stage, float fraction) {
                if (job != started) return;
                started.progress = new Progress(stage.getTitle(), Math.round(fraction * 100), true);
                changed.accept(recording);
            }

            @Override public void onPartial(Transcript transcript) {}

            @Override public void onDone(Transcript transcript, ProcessingStats stats) {
                if (job != started) return;
                if (transcript.getUtterances().isEmpty()) {
                    complete(started, null, "В записи не удалось распознать речь.");
                    return;
                }
                started.progress = new Progress("Сохраняю расшифровку", 100, false);
                changed.accept(recording);
                io.execute(() -> persist(started, transcript, title, duration, scope));
            }

            @Override public void onError(Throwable error) {
                String detail = error.getMessage();
                String message = detail != null && detail.startsWith("В APK нет моделей:")
                        ? "В этой сборке нет моделей распознавания."
                        : "Не удалось расшифровать запись. Можно повторить попытку.";
                complete(started, null, message);
            }
        });
    }

    void cancel(File recording) {
        Progress active = progress(recording);
        if (active == null || !active.cancellable()) return;
        Job cancelled = job;
        job = null;
        if (cancelled.task != null) cancelled.task.cancel();
        changed.accept(recording);
    }

    void close() {
        closed = true;
        if (job != null && job.progress.cancellable() && job.task != null) job.task.cancel();
        job = null;
    }

    private void persist(Job started, Transcript output, String title, int duration, String scope) {
        File recording = started.recording;
        try {
            List<ReflectTranscript.Line> lines = new ArrayList<>(output.getUtterances().size());
            for (Utterance utterance : output.getUtterances()) {
                lines.add(new ReflectTranscript.Line(utterance.getSpeakerId(),
                        Math.toIntExact(utterance.getStartMs()), Math.toIntExact(utterance.getEndMs()),
                        utterance.getText(), utterance.getEmotionProbabilities()));
            }
            ReflectTranscript transcript = new ReflectTranscript(lines);
            if (!store.saveIfAbsent(recording, transcript)) {
                ReflectTranscript existing = store.load(recording);
                main.post(() -> complete(started, existing, "У записи уже есть расшифровка."));
                return;
            }
            boolean queued = scope == null || backend.onGenerated(recording, store.title(recording, title),
                    duration, scope);
            main.post(() -> complete(started, transcript, queued ? null
                    : "Расшифровка сохранена на устройстве. Отправку можно повторить из меню записи."));
        } catch (IOException | RuntimeException failure) {
            main.post(() -> complete(started, null, SAVE_FAILED));
        }
    }

    private void complete(Job started, ReflectTranscript transcript, String message) {
        if (closed || job != started) return;
        job = null;
        finished.accept(new Result(started.recording, transcript, message));
    }

    private static final class Job {
        final File recording;
        ReflectEngine.Task task;
        Progress progress = new Progress("Подготовка к распознаванию", 0, true);

        Job(File recording) { this.recording = recording; }
    }
}
