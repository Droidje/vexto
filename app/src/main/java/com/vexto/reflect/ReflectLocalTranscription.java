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

/** Persists a core checkpoint, then atomically refines only its own unchanged transcript. */
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
                new TranscriptionOptions(), new ReflectEngine.DetailedListener() {
            @Override public void onProgress(Stage stage, float fraction) {
                if (job != started) return;
                started.progress = new Progress(stage.getTitle(), Math.round(fraction * 100), true);
                changed.accept(recording);
            }

            @Override public void onPartial(Transcript transcript) {}

            @Override public void onCoreReady(Transcript transcript, String artifacts) {
                if (job != started) return;
                io.execute(() -> {
                    synchronized (started) {
                        if (started.finalScheduled) return;
                        try {
                            ReflectTranscript core = convert(transcript);
                            store.saveEngineArtifacts(recording, artifacts);
                            if (!core.lines.isEmpty() && store.saveIfAbsent(recording, core)) started.provisional = core;
                        } catch (IOException | RuntimeException ignored) {
                            // Engine still retains its durable originals, even when the UI sidecar cannot be written.
                        }
                    }
                });
            }

            @Override public void onDone(Transcript transcript, ProcessingStats stats) {
                if (job != started) return;
                if (transcript.getUtterances().isEmpty()) {
                    complete(started, null, "В записи не удалось распознать речь.");
                    return;
                }
                started.finalScheduled = true;
                started.progress = new Progress("Сохраняю расшифровку", 100, false);
                changed.accept(recording);
                io.execute(() -> persist(started, transcript, stats, title, duration, scope));
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

    private void persist(Job started, Transcript output, ProcessingStats stats, String title, int duration, String scope) {
        File recording = started.recording;
        try {
            ReflectTranscript transcript = convert(output);
            boolean saved;
            synchronized (started) {
                if (!stats.getArtifactDirectory().isEmpty()) store.saveEngineArtifacts(recording, stats.getArtifactDirectory());
                saved = started.provisional != null
                        ? store.replaceIfSame(recording, started.provisional, transcript)
                        : store.saveIfAbsent(recording, transcript);
            }
            if (!saved) {
                ReflectTranscript existing = store.load(recording);
                main.post(() -> complete(started, existing, "У записи уже есть расшифровка."));
                return;
            }
            boolean queued = scope == null || backend.onGenerated(recording, store.title(recording, title),
                    duration, scope);
            String message = !stats.getComplete() ? "Часть аудио не распознана. Доступный текст и оригинальные результаты сохранены."
                    : !stats.getSelectionResolved() ? "Текст сохранён. Для части фрагментов выбор модели не подтверждён."
                    : !stats.getFullProfileCompleted() ? "Текст сохранён. Некоторые дополнительные этапы не завершились."
                    : !queued ? "Расшифровка сохранена на устройстве. Отправку можно повторить из меню записи." : null;
            main.post(() -> complete(started, transcript, message));
        } catch (IOException | RuntimeException failure) {
            main.post(() -> complete(started, null, SAVE_FAILED));
        }
    }

    private static ReflectTranscript convert(Transcript output) {
        List<ReflectTranscript.Line> lines = new ArrayList<>(output.getUtterances().size());
        for (Utterance utterance : output.getUtterances()) {
            lines.add(new ReflectTranscript.Line(utterance.getSpeakerId(),
                    Math.toIntExact(utterance.getStartMs()), Math.toIntExact(utterance.getEndMs()), utterance.getText()));
        }
        return new ReflectTranscript(lines);
    }

    private void complete(Job started, ReflectTranscript transcript, String message) {
        if (closed || job != started) return;
        job = null;
        finished.accept(new Result(started.recording, transcript, message));
    }

    private static final class Job {
        final File recording;
        ReflectEngine.Task task;
        volatile boolean finalScheduled;
        ReflectTranscript provisional;
        Progress progress = new Progress("Подготовка к распознаванию", 0, true);

        Job(File recording) { this.recording = recording; }
    }
}
