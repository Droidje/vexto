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
import com.vexto.engine.models.BundledModelPackException;
import com.vexto.engine.models.OfflineModelPackException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Persists a core checkpoint, then atomically refines only its own unchanged transcript. */
final class ReflectLocalTranscription {
    record Progress(Stage stage, int percent, int speakers) {
        boolean cancellable() { return stage != null; }
        String title() { return cancellable() ? stage.getTitle() : "Сохраняю расшифровку"; }
    }
    record Result(File recording, ReflectTranscript transcript, String message) {}

    private static final String SAVE_FAILED = "Не удалось сохранить расшифровку.";
    private final Context context;
    private final ReflectTranscriptStore store;
    private final SpeakerProfiles profiles;
    private final Executor io;
    private final Consumer<File> changed;
    private final Consumer<Result> finished;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Job job;
    private boolean closed;

    ReflectLocalTranscription(Context context, ReflectTranscriptStore store, SpeakerProfiles profiles,
                              Executor io, Consumer<File> changed, Consumer<Result> finished) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.profiles = profiles;
        this.io = io;
        this.changed = changed;
        this.finished = finished;
    }

    boolean busy() { return job != null; }

    Progress progress(File recording) {
        return job != null && job.recording.equals(recording) ? job.progress : null;
    }

    void start(File recording) {
        if (closed || busy()) return;
        Job started = new Job(recording);
        job = started;
        changed.accept(recording);
        started.task = ReflectEngine.transcribe(context, Uri.fromFile(recording),
                new TranscriptionOptions(), new ReflectEngine.DetailedListener() {
            @Override public void onProgress(Stage stage, float fraction) {
                if (job != started) return;
                started.progress = new Progress(stage, Math.round(fraction * 100), started.progress.speakers());
                changed.accept(recording);
            }

            @Override public void onPartial(Transcript transcript) {
                if (job != started) return;
                int speakers = transcript.getSpeakers().size();
                Progress previous = started.progress;
                if (speakers == 0 || speakers == previous.speakers()) return;
                started.progress = new Progress(previous.stage(), previous.percent(), speakers);
                changed.accept(recording);
            }

            @Override public void onCoreReady(Transcript transcript, String artifacts) {
                if (job != started) return;
                onPartial(transcript);
                io.execute(() -> {
                    synchronized (started) {
                        if (started.finalScheduled) return;
                        try {
                            ReflectTranscript core = convert(transcript);
                            store.saveEngineArtifacts(recording, artifacts);
                            if (!core.lines.isEmpty() && store.saveIfAbsent(recording, core)) {
                                started.provisional = core;
                                registerSpeakers(recording, core, transcript);
                            }
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
                started.progress = new Progress(null, 100, transcript.getSpeakers().size());
                changed.accept(recording);
                io.execute(() -> persist(started, transcript, stats));
            }

            @Override public void onError(Throwable error) {
                String message = "Не удалось расшифровать запись. Можно повторить попытку.";
                if (error instanceof BundledModelPackException) {
                    message = error.getMessage();
                } else if (error instanceof OfflineModelPackException) {
                    message = "Установите проверенный офлайн-пакет моделей и повторите расшифровку.";
                }
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

    private void persist(Job started, Transcript output, ProcessingStats stats) {
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
            String profileWarning = null;
            if (started.provisional != null) {
                try { profiles.retainAcrossRefinement(recording, started.provisional, transcript); }
                catch (IOException | RuntimeException unavailable) {
                    profileWarning = "Расшифровка сохранена. Не удалось перенести привязки спикеров.";
                }
            }
            String registrationWarning = registerSpeakers(recording, transcript, output);
            if (registrationWarning != null) profileWarning = registrationWarning;
            String statsWarning = !stats.getComplete() ? "Часть аудио не распознана. Доступный текст и оригинальные результаты сохранены."
                    : !stats.getSelectionResolved() ? "Текст сохранён. Для части фрагментов выбор модели не подтверждён."
                    : !stats.getFullProfileCompleted() ? "Текст сохранён. Некоторые дополнительные этапы не завершились."
                    : null;
            String message = profileWarning == null ? statsWarning
                    : statsWarning == null ? profileWarning : statsWarning + " " + profileWarning;
            main.post(() -> complete(started, transcript, message));
        } catch (IOException | RuntimeException failure) {
            main.post(() -> complete(started, null, SAVE_FAILED));
        }
    }

    private String registerSpeakers(File recording, ReflectTranscript transcript, Transcript output) {
        String warning = null;
        try { profiles.saveEmbeddings(recording, transcript, output.getSpeakers()); }
        catch (IOException | RuntimeException unavailable) {
            warning = "Расшифровка сохранена. Не удалось сохранить голосовые профили.";
        }
        try { profiles.assign(recording, transcript); }
        catch (IOException | RuntimeException unavailable) {
            return "Расшифровка сохранена. Не удалось обновить список спикеров.";
        }
        return warning;
    }

    private static ReflectTranscript convert(Transcript output) {
        List<ReflectTranscript.Line> lines = new ArrayList<>(output.getUtterances().size());
        for (Utterance utterance : output.getUtterances()) {
            lines.add(new ReflectTranscript.Line(utterance.getSpeakerId(),
                    Math.toIntExact(utterance.getStartMs()), Math.toIntExact(utterance.getEndMs()),
                    utterance.getText(), utterance.getEmotionProbabilities()));
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
        Progress progress = new Progress(Stage.Preparing, 0, 0);

        Job(File recording) { this.recording = recording; }
    }
}
