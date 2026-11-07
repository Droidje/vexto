package com.vexto.reflect;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns microphone plus user-authorized app playback capture independently of the Activity. */
public final class CallRecordingService extends Service {
    record State(boolean active, boolean paused, long elapsedMs, int peakAmplitude) {}
    record Result(File recording, String message) {}
    interface Observer {
        void onState(State state);
        void onFinished(File recording, String message);
    }

    private static final String CHANNEL = "call_recording";
    private static final int NOTIFICATION = 3702;
    private static final String START = "com.vexto.reflect.START_CALL_RECORDING";
    private static final String PAUSE = "com.vexto.reflect.PAUSE_CALL_RECORDING";
    private static final String FINISH = "com.vexto.reflect.FINISH_CALL_RECORDING";
    private static final String TOKEN = "projection";
    private static final String REQUEST = "capture_id";
    private static final Object STORAGE_LOCK = new Object();
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Set<Observer> observers = new LinkedHashSet<>();
    private static CallRecordingService running;
    private static Result lastResult;
    private static boolean requested;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CallRecordingSession session;
    private MediaProjection projection;
    private PowerManager.WakeLock cpu;
    private NotificationManager notifications;
    private boolean finishing;
    private String requestId;
    private State terminalState = new State(false, false, 0, 0);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (session == null || finishing) return;
            changed();
            notifications.notify(NOTIFICATION, notification());
            main.postDelayed(this, 500);
        }
    };
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            if (session != null && !finishing) complete(false, "Системный захват остановлен. Запись сохранена.");
        }
    };

    static void observe(Observer observer) {
        observers.add(observer);
        observer.onState(state());
    }

    static void removeObserver(Observer observer) { observers.remove(observer); }
    static boolean active() { return requested || running != null && (running.session != null || running.finishing); }

    static State state() {
        CallRecordingService service = running;
        if (service == null) return new State(requested, false, 0, 0);
        if (service.session == null) return service.terminalState;
        return new State(true, service.session.paused(), service.session.elapsedMillis(), service.session.peakAmplitude());
    }

    static Result consumeResult() {
        Result result = lastResult;
        lastResult = null;
        return result;
    }

    static void start(Context context, int resultCode, Intent authorization) {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Call capture requires Android 10");
        if (resultCode != Activity.RESULT_OK || authorization == null) throw new IllegalArgumentException("User authorization is required");
        if (active()) throw new IllegalStateException("A call capture is already active");
        requested = true;
        lastResult = null;
        try {
            context.startForegroundService(new Intent(context, CallRecordingService.class)
                    .setAction(START).putExtra(TOKEN, authorization).putExtra(REQUEST, UUID.randomUUID().toString()));
        } catch (RuntimeException failure) {
            requested = false;
            throw failure;
        }
    }

    static void pause() {
        if (running == null || running.session == null || running.finishing) return;
        running.session.pause();
        running.changed();
    }

    static void resume() {
        if (running == null || running.session == null || running.finishing) return;
        running.session.resume();
        running.changed();
    }

    static void finish() {
        if (running != null) running.complete(false, null);
    }

    static void cancel() {
        if (running != null) running.complete(true, null);
    }

    /** Invoke off the UI thread on app startup. MediaProjection authorization cannot survive process death. */
    static void recover(Context context) throws IOException {
        synchronized (STORAGE_LOCK) {
            if (!active()) CallRecordingSession.recover(context.getApplicationContext());
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        notifications = getSystemService(NotificationManager.class);
        var channel = new NotificationChannel(CHANNEL, "Запись звонка", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Микрофон и разрешённый системный звук");
        notifications.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && (FINISH.equals(intent.getAction()) || PAUSE.equals(intent.getAction()))) {
            if (requestId == null || !requestId.equals(intent.getStringExtra(REQUEST))) {
                if (session == null && !finishing) stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            if (FINISH.equals(intent.getAction())) complete(false, null);
            else if (session != null && session.paused()) resume();
            else pause();
            return START_NOT_STICKY;
        }
        if (session != null || finishing) return START_NOT_STICKY;
        try {
            if (Build.VERSION.SDK_INT < 29 || intent == null || !START.equals(intent.getAction())) {
                throw new IllegalStateException("Call capture cannot restart without user authorization");
            }
            requestId = intent.getStringExtra(REQUEST);
            if (requestId == null) throw new IllegalStateException("No capture request identity");
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= 30) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION, notification(), types);
            Intent authorization = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(TOKEN, Intent.class) : intent.getParcelableExtra(TOKEN);
            if (authorization == null) throw new IllegalStateException("No playback capture authorization");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, authorization);
            if (projection == null) throw new IllegalStateException("Playback capture authorization failed");
            projection.registerCallback(projectionCallback, main);
            synchronized (STORAGE_LOCK) {
                session = new CallRecordingSession(this, projection, main,
                        error -> complete(false, "Захват звука прерван. Доступная часть записи сохранена."));
                session.start();
            }
            requested = false;
            PowerManager power = getSystemService(PowerManager.class);
            cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vexto:call-recording");
            cpu.setReferenceCounted(false);
            cpu.acquire(12 * 60 * 60 * 1000L);
            main.post(tick);
        } catch (IOException | RuntimeException failure) {
            requested = false;
            complete(false, "Не удалось включить микрофон и захват звука приложений.");
        }
        return START_NOT_STICKY;
    }

    private void complete(boolean discard, String message) {
        if (finishing) return;
        finishing = true;
        requested = false;
        main.removeCallbacks(tick);
        CallRecordingSession stopped = session;
        if (stopped != null) terminalState = new State(true, stopped.paused(), stopped.elapsedMillis(), 0);
        session = null;
        releaseProjection();
        io.execute(() -> {
            File recording = null;
            String resultMessage = message;
            try {
                if (stopped != null) synchronized (STORAGE_LOCK) { recording = stopped.finish(this, discard); }
                if (!discard && recording == null && resultMessage == null) resultMessage = "Запись слишком короткая.";
            } catch (IOException | RuntimeException failure) {
                resultMessage = "Не удалось завершить запись. Сохранённый фрагмент будет восстановлен при открытии приложения.";
            }
            File saved = recording;
            String notice = resultMessage;
            main.post(() -> {
                lastResult = new Result(saved, notice);
                terminalState = new State(false, false, 0, 0);
                finishing = false;
                if (running == this) running = null;
                changed();
                for (var observer : new ArrayList<>(observers)) observer.onFinished(saved, notice);
                releaseCpu();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        });
    }

    private void changed() {
        State state = state();
        for (var observer : new ArrayList<>(observers)) observer.onState(state);
    }

    private void releaseProjection() {
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
    }

    private void releaseCpu() {
        if (cpu != null && cpu.isHeld()) cpu.release();
    }

    private Notification notification() {
        State state = state();
        String body = state.active() ? state.paused() ? "На паузе" : "Записываются микрофон и доступный звук приложений"
                : "Включается микрофон и захват звука";
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 3702, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 3703, command(PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent finish = PendingIntent.getService(this, 3704, command(FINISH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Vexto · Запись звонка").setContentText(body).setContentIntent(content)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, state.paused() ? "Продолжить" : "Пауза", pause).build())
                .addAction(new Notification.Action.Builder(null, "Завершить", finish).build()).build();
    }

    private Intent command(String action) {
        return new Intent(this, CallRecordingService.class).setAction(action).putExtra(REQUEST, requestId)
                .setData(Uri.parse("vexto:call/" + requestId));
    }

    @Override public void onDestroy() {
        main.removeCallbacks(tick);
        if (session != null && !finishing) complete(false, "Запись остановлена системой. Доступная часть сохранена.");
        releaseProjection();
        if (!finishing) releaseCpu();
        if (running == this && !finishing) running = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
