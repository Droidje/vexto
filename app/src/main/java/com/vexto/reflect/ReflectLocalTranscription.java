package com.vexto.reflect;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.vexto.engine.model.Stage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Main-thread observer of the process-wide transcription service. */
final class ReflectLocalTranscription {
    record Progress(Stage stage, int percent, int speakers, int overallPercent, boolean backgroundEmotions) {
        Progress(Stage stage, int percent, int speakers, int overallPercent) {
            this(stage, percent, speakers, overallPercent, false);
        }
        Progress(Stage stage, int percent, int speakers) {
            this(stage, percent, speakers, ReflectPipelineProgress.overall(stage, percent));
        }

        Progress advance(Stage next, int nextPercent) {
            int overall = Math.max(overallPercent, ReflectPipelineProgress.overall(next, nextPercent));
            return new Progress(next, nextPercent, speakers, backgroundEmotions ? 100 : overall, backgroundEmotions);
        }

        Progress withSpeakers(int count) { return new Progress(stage, percent, count, overallPercent, backgroundEmotions); }
        Progress asBackgroundEmotions() { return new Progress(Stage.Emotion, 0, speakers, 100, true); }
        boolean cancellable() { return stage != null; }
        String title() { return stage == Stage.Overlap ? stage.getTitle() : backgroundEmotions ? "эмоции обрабатываются"
                : cancellable() ? stage.getTitle() : "Сохраняю расшифровку"; }
    }
    record Result(File recording, ReflectTranscript transcript, String message, boolean metadataOnly) {
        Result(File recording, ReflectTranscript transcript, String message) { this(recording, transcript, message, false); }
    }

    static final Progress WAITING = new Progress(Stage.Preparing, 0, 0, 0);
    private static final String TAG = "VextoTranscription";
    private static final String START_FAILED = "Не удалось запустить обработку. Откройте приложение и повторите попытку.";
    private static final Set<ReflectLocalTranscription> observers = new LinkedHashSet<>();
    private static boolean loaded;
    private static PendingTranscription pending;

    private final Context context;
    private final Consumer<File> changed;
    private final Consumer<Result> finished;
    private boolean closed;

    ReflectLocalTranscription(Context context, Consumer<File> changed, Consumer<Result> finished) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        this.finished = finished;
        observers.add(this);
    }

    boolean busy() { return activeRecording() != null; }

    File activeRecording() {
        PendingTranscription request = pending(context);
        return request == null ? null : request.recording();
    }

    Progress progress(File recording) {
        File active = activeRecording();
        if (active == null || !active.equals(recording)) return null;
        if (!ReflectTranscriptionService.isRunning() && pending(context).dialogueReady(context))
            return WAITING.asBackgroundEmotions();
        return ReflectTranscriptionService.progress();
    }

    boolean start(File recording) {
        if (closed || busy()) return false;
        try {
            PendingTranscription request = PendingTranscription.create(context, recording);
            request.write(context);
            pending = request;
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Could not persist transcription request" + ": " + failure.getClass().getSimpleName());
            finished.accept(new Result(recording, null, "Не удалось сохранить задачу расшифровки."));
            return false;
        }
        if (!launch()) {
            forget(context, pending);
            finished.accept(new Result(recording, null, START_FAILED));
            return false;
        }
        notifyChanged(recording);
        return true;
    }

    void resume() {
        if (closed || !busy() || ReflectTranscriptionService.isRunning()) return;
        if (!launch()) finished.accept(new Result(activeRecording(), null, START_FAILED));
    }

    private boolean launch() {
        try {
            context.startForegroundService(new Intent(context, ReflectTranscriptionService.class));
            return true;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not start transcription service" + ": " + failure.getClass().getSimpleName());
            return false;
        }
    }

    void cancel(File recording) {
        Progress active = progress(recording);
        if (active != null && active.cancellable()) cancel(context, pending);
    }

    void close() {
        closed = true;
        observers.remove(this);
    }

    static PendingTranscription pending(Context context) {
        if (!loaded) {
            loaded = true;
            try {
                pending = PendingTranscription.read(context);
            } catch (IOException | RuntimeException invalid) {
                Log.w(TAG, "Could not recover transcription request" + ": " + invalid.getClass().getSimpleName());
            }
        }
        return pending;
    }

    static boolean forget(Context context, PendingTranscription request) {
        if (request == null || !request.equals(pending(context))) return false;
        try {
            PendingTranscription.clear(context);
            pending = null;
            return true;
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Could not clear transcription request" + ": " + failure.getClass().getSimpleName());
            return false;
        }
    }

    static void cancel(Context context, PendingTranscription request) {
        if (!forget(context, request)) return;
        ReflectTranscriptionService.cancel(request);
        notifyChanged(request.recording());
    }

    static void notifyChanged(File recording) {
        for (var observer : new ArrayList<>(observers)) observer.changed.accept(recording);
    }

    static void notifyFinished(Result result) {
        for (var observer : new ArrayList<>(observers)) observer.finished.accept(result);
    }
}
