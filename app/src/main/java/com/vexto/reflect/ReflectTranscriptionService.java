package com.vexto.reflect;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import com.vexto.engine.ReflectEngine;
import com.vexto.engine.audio.AudioInputTooLongException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the current inference independently of the screen and notification that observe it. */
public final class ReflectTranscriptionService extends Service {
    private static final String TAG = "VextoTranscription";
    private static final String CHANNEL = "local_transcription";
    private static final String ACTION_CANCEL = "com.vexto.reflect.CANCEL_TRANSCRIPTION";
    private static final String EXTRA_JOB_ID = "job_id";
    private static final int NOTIFICATION = 3701;
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static ReflectTranscriptionService active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ReflectTranscriptStore store;
    private SpeakerProfiles profiles;
    private NotificationManager notifications;
    private PowerManager.WakeLock cpu;
    private Job job;
    private int lastStartId;
    private long lastNotification;

    static boolean isRunning() { return active != null && active.job != null; }

    static ReflectLocalTranscription.Progress progress() {
        return isRunning() ? active.job.progress : ReflectLocalTranscription.WAITING;
    }

    static void cancel(PendingTranscription request) {
        if (isRunning() && request.equals(active.job.request)) {
            // An already committed preview remains available; no new write can begin after cancel.
            synchronized (active.job) {
                active.interrupt();
            }
            active.stopForegroundAndSelf();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        store = new ReflectTranscriptStore(getFilesDir());
        profiles = new SpeakerProfiles(this);
        notifications = getSystemService(NotificationManager.class);
        var channel = new NotificationChannel(CHANNEL, "Локальная расшифровка", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Ход обработки записей на устройстве");
        notifications.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        PendingTranscription request = ReflectLocalTranscription.pending(this);
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            if (request != null && request.jobId().equals(intent.getStringExtra(EXTRA_JOB_ID))
                    && progress().cancellable()) ReflectLocalTranscription.cancel(this, request);
            if (job == null) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            Notification initial = notification(request);
            if (Build.VERSION.SDK_INT >= 29) {
                int type = Build.VERSION.SDK_INT >= 35 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                startForeground(NOTIFICATION, initial, type);
            } else {
                startForeground(NOTIFICATION, initial);
            }
            if (request == null) {
                stopForegroundAndSelf();
            } else if (job == null) {
                acquireCpu();
                start(request);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not run transcription service" + ": " + failure.getClass().getSimpleName());
            interrupt();
            stopForegroundAndSelf();
            if (request != null) ReflectLocalTranscription.notifyFinished(new ReflectLocalTranscription.Result(
                    request.recording(), null, "Обработка приостановлена. Откройте приложение, чтобы продолжить."));
        }
        return START_NOT_STICKY;
    }

    private void acquireCpu() {
        if (cpu == null) {
            PowerManager power = getSystemService(PowerManager.class);
            cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vexto:transcription");
            cpu.setReferenceCounted(false);
        }
        // Android 15 also imposes a time budget on media processing services.
        if (!cpu.isHeld()) cpu.acquire(6 * 60 * 60 * 1000L);
    }

    private void start(PendingTranscription request) {
        Job started = new Job(request);
        job = started;
        started.backgroundEmotions = request.dialogueReady(this);
        if (started.backgroundEmotions) started.progress = started.progress.asBackgroundEmotions();
        File recording = request.recording();
        changed(started);
        started.task = ReflectEngine.transcribeResumable(getApplicationContext(), Uri.fromFile(recording),
                new TranscriptionOptions(null, true, request.processingMode()), request.jobId(), new ReflectEngine.EnrichmentListener() {
            @Override public void onProgress(Stage stage, float fraction) {
                if (job != started) return;
                int percent = Math.round(fraction * 100);
                var previous = started.progress;
                if (stage == previous.stage() && percent == previous.percent()) return;
                started.progress = previous.advance(stage, percent);
                changed(started);
            }

            @Override public void onPartial(Transcript transcript) {
                if (job != started) return;
                int speakers = transcript.getSpeakers().size();
                var previous = started.progress;
                if (speakers == 0 || speakers == previous.speakers()) return;
                started.progress = previous.withSpeakers(speakers);
                changed(started);
            }

            @Override public void onCoreReady(Transcript transcript, String artifacts) {
                if (job != started) return;
                onPartial(transcript);
                io.execute(() -> publishCore(started, transcript, artifacts));
            }

            @Override public void onDialogueReady(Transcript transcript, String artifacts, String warning) {
                if (job != started) return;
                started.backgroundEmotions = true;
                started.progress = started.progress.withSpeakers(transcript.getSpeakers().size()).asBackgroundEmotions();
                changed(started);
                io.execute(() -> publishDialogueReady(started, transcript, artifacts, warning));
            }

            @Override public void onDone(Transcript transcript, ProcessingStats stats) {
                if (job != started) return;
                if (transcript.getUtterances().isEmpty()) {
                    complete(started, null, "В записи не удалось распознать речь.");
                    return;
                }
                started.progress = started.backgroundEmotions
                        ? started.progress.advance(started.progress.stage(), 100)
                        : new ReflectLocalTranscription.Progress(null, 100, transcript.getSpeakers().size());
                changed(started);
                io.execute(() -> publishFinal(started, transcript, stats));
            }

            @Override public void onError(Throwable error) {
                String message = "Не удалось расшифровать запись. Можно повторить попытку.";
                if (error instanceof AudioInputTooLongException) {
                    message = "Запись длиннее 4 часов. Разделите её на несколько частей.";
                } else if (error instanceof BundledModelPackException) {
                    message = error.getMessage();
                } else if (error instanceof OfflineModelPackException) {
                    message = "Установите проверенный офлайн-пакет моделей и повторите расшифровку.";
                }
                if (started.backgroundEmotions) message = started.progress.stage() == Stage.Overlap
                        ? "Не удалось проверить перебивания. Расшифровка сохранена."
                        : "Не удалось определить эмоции. Расшифровка сохранена.";
                complete(started, null, message);
            }
        });
    }

    private void publishCore(Job started, Transcript output, String artifacts) {
        if (started.interrupted) return;
        File recording = started.request.recording();
        try {
            ReflectTranscript core = convert(output);
            if (core.lines.isEmpty()) return;
            synchronized (started) {
                if (started.interrupted || !store.saveEngineCore(recording, started.request.jobId(), core)) return;
                store.saveEngineArtifacts(recording, artifacts);
            }
            registerSpeakers(recording, core, output);
            main.post(() -> {
                if (job == started) changed(started);
            });
        } catch (IOException | RuntimeException unavailable) {
            // The inference checkpoint is durable even if the UI cannot publish the preview.
            Log.w(TAG, "Could not publish provisional transcript", unavailable);
        }
    }

    private void publishDialogueReady(Job started, Transcript output, String artifacts, String warning) {
        if (started.interrupted) return;
        File recording = started.request.recording();
        try {
            ReflectTranscriptStore.EngineSave saved;
            synchronized (started) {
                if (started.interrupted) return;
                saved = store.saveEngineFinal(recording, started.request.jobId(), convert(output));
                if (!saved.owned()) return;
                store.saveEngineArtifacts(recording, artifacts);
            }
            if (saved.provisional() != null) {
                try { profiles.retainAcrossRefinement(recording, saved.provisional(), saved.transcript()); }
                catch (IOException | RuntimeException unavailable) { Log.w(TAG, "Could not retain speaker links", unavailable); }
            }
            registerSpeakers(recording, saved.transcript(), output);
            new ReflectTranscriptionOutcomes(this).finish(recording, true, null);
            main.post(() -> {
                if (job != started || started.interrupted) return;
                changed(started);
                ReflectLocalTranscription.notifyFinished(new ReflectLocalTranscription.Result(recording, saved.transcript(), warning));
            });
        } catch (IOException | RuntimeException unavailable) {
            Log.w(TAG, "Could not publish main dialogue", unavailable);
        }
    }

    private void publishEmotions(Job started, Transcript output) {
        if (started.interrupted) return;
        try {
            ReflectTranscriptStore.EngineSave saved;
            synchronized (started) {
                if (started.interrupted) return;
                saved = store.saveEngineEmotions(started.request.recording(), started.request.jobId(), convert(output));
            }
            boolean missing = output.getUtterances().stream().anyMatch(line ->
                    !line.getText().trim().isEmpty() && line.getEmotionProbabilities() == null);
            String message = missing ? "Для части реплик эмоции не определены. Расшифровка сохранена." : null;
            main.post(() -> complete(started, saved.transcript(), message));
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Could not enrich dialogue emotions", failure);
            main.post(() -> complete(started, null, "Не удалось сохранить эмоции. Расшифровка сохранена."));
        }
    }

    private void publishFinal(Job started, Transcript output, ProcessingStats stats) {
        if (started.interrupted) return;
        if (started.backgroundEmotions) { publishEmotions(started, output); return; }
        File recording = started.request.recording();
        try {
            ReflectTranscript converted = convert(output);
            if (converted.lines.isEmpty()) {
                main.post(() -> complete(started, null, "В записи не удалось распознать речь."));
                return;
            }
            var saved = store.saveEngineFinal(recording, started.request.jobId(), converted);
            ReflectTranscript transcript = saved.transcript();
            if (!saved.owned()) {
                main.post(() -> complete(started, transcript, "У записи уже есть расшифровка."));
                return;
            }
            if (!stats.getArtifactDirectory().isEmpty()) store.saveEngineArtifacts(recording, stats.getArtifactDirectory());
            String profileWarning = null;
            if (saved.provisional() != null) {
                try { profiles.retainAcrossRefinement(recording, saved.provisional(), transcript); }
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
                    : statsWarning == null ? profileWarning : profileWarning + " " + statsWarning;
            main.post(() -> complete(started, transcript, message));
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Could not publish transcript", failure);
            main.post(() -> complete(started, null, "Не удалось сохранить расшифровку."));
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

    static ReflectTranscript convert(Transcript output) {
        List<ReflectTranscript.Line> lines = new ArrayList<>(output.getUtterances().size());
        for (Utterance utterance : output.getUtterances()) {
            // Nemotron intervals without assigned ASR words remain in the engine artifacts.
            // They have no display text and must not reject the rest of the transcript.
            if (utterance.getText().trim().isEmpty()) continue;
            lines.add(new ReflectTranscript.Line(utterance.getSpeakerId(),
                    Math.toIntExact(utterance.getStartMs()), Math.toIntExact(utterance.getEndMs()),
                    utterance.getText(), utterance.getEmotionProbabilities(),
                    output.speaker(utterance.getSpeakerId()).getName(), utterance.getOverlapDetected()));
        }
        return new ReflectTranscript(lines);
    }

    private void changed(Job started) {
        var current = started.progress;
        long now = SystemClock.uptimeMillis();
        boolean notificationAllowed = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (notificationAllowed && (now - lastNotification >= 800 || !current.cancellable())) {
            lastNotification = now;
            notifications.notify(NOTIFICATION, notification(started.request));
        }
        ReflectLocalTranscription.notifyChanged(started.request.recording());
    }

    private Notification notification(PendingTranscription request) {
        var current = job == null ? ReflectLocalTranscription.WAITING : job.progress;
        Intent launch = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Vexto · локальная расшифровка")
                .setContentText(current.title())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setProgress(100, current.overallPercent(), current.overallPercent() == 0 || !current.cancellable());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (request != null && current.cancellable()) {
            Intent cancel = new Intent(this, ReflectTranscriptionService.class).setAction(ACTION_CANCEL)
                    .setData(Uri.parse("vexto:transcription/" + request.jobId())).putExtra(EXTRA_JOB_ID, request.jobId());
            PendingIntent action = PendingIntent.getService(this, 0, cancel, PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "Отменить", action).build());
        }
        return builder.build();
    }

    private void complete(Job started, ReflectTranscript transcript, String message) {
        if (job != started) return;
        new ReflectTranscriptionOutcomes(this).finish(started.request.recording(), transcript != null || started.backgroundEmotions, message);
        job = null;
        ReflectLocalTranscription.forget(this, started.request);
        stopForegroundAndSelf();
        ReflectLocalTranscription.notifyFinished(new ReflectLocalTranscription.Result(started.request.recording(), transcript, message, started.backgroundEmotions));
    }

    private void interrupt() {
        Job interrupted = job;
        job = null;
        if (interrupted != null) {
            interrupted.interrupted = true;
            if (interrupted.task != null) interrupted.task.cancel();
        }
    }

    private void stopForegroundAndSelf() {
        if (cpu != null && cpu.isHeld()) cpu.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelfResult(lastStartId);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (job != null && job.progress.cancellable() && !job.backgroundEmotions) {
            interrupt();
            stopForegroundAndSelf();
        }
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        interrupt();
        stopForegroundAndSelf();
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        interrupt();
        if (cpu != null && cpu.isHeld()) cpu.release();
        io.execute(profiles::close);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class Job {
        final PendingTranscription request;
        ReflectEngine.Task task;
        volatile boolean interrupted;
        volatile boolean backgroundEmotions;
        ReflectLocalTranscription.Progress progress = ReflectLocalTranscription.WAITING;

        Job(PendingTranscription request) { this.request = request; }
    }
}
