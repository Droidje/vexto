package com.vexto.reflect;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.vexto.engine.ReflectEmotionEngine;
import com.vexto.engine.model.EmotionProbabilities;
import com.vexto.engine.model.Utterance;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One process-scoped drainer. Scheduling and queue discovery never depend on an Activity. */
final class ReflectEmotionCoordinator {
    private static final int JOB_ID = 0x454d4f;
    private static ReflectEmotionCoordinator instance;
    private final Context context;
    private final ReflectTranscriptStore store;
    private final ReflectEmotionQueue queue;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    // Only io mutates run/batch; cancellation flags and task cross main/native callback threads.
    private volatile Run run;
    private Batch batch;

    static synchronized ReflectEmotionCoordinator get(Context context) {
        if (instance == null) instance = new ReflectEmotionCoordinator(context.getApplicationContext());
        return instance;
    }

    private ReflectEmotionCoordinator(Context context) {
        this.context = context;
        store = new ReflectTranscriptStore(context.getFilesDir());
        queue = new ReflectEmotionQueue(context);
        ReflectTranscriptStore.addListener(this::enqueue);
    }

    void reconcile() {
        io.execute(() -> {
            File directory = new File(context.getFilesDir(), "recordings");
            queue.pruneMissing(directory);
            File[] recordings = directory.listFiles(File::isFile);
            if (recordings != null) {
                Arrays.sort(recordings, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
                for (File recording : recordings) discover(recording);
            }
            scheduleIfIdle();
        });
    }

    private void enqueue(File recording) {
        io.execute(() -> { discover(recording); scheduleIfIdle(); });
    }

    private void discover(File recording) {
        try {
            File directory = new File(context.getFilesDir(), "recordings").getCanonicalFile();
            if (!directory.equals(recording.getCanonicalFile().getParentFile())) return;
            if (recording.isFile()) queue.enqueue(recording, store.snapshot(recording));
        } catch (IOException | RuntimeException failure) {
            Log.w("ReflectEmotions", "Cannot discover saved transcript", failure);
        }
    }

    /** Called by the JobService. Completion runs on main after resources have been released. */
    void start(Runnable completed) {
        io.execute(() -> {
            if (run != null) { main.post(completed); return; }
            run = new Run(completed);
            drain();
        });
    }

    void stop() {
        io.execute(() -> {
            if (run != null) run.stopped = true;
            if (batch != null) {
                batch.cancelled = true;
                ReflectEmotionEngine.Task task = batch.task;
                if (task != null) task.cancel();
            }
            // JobScheduler owns rescheduling; committed sidecar/cursor remain intact.
            run = null;
            batch = null;
        });
    }

    private void drain() {
        if (run == null || batch != null) return;
        ReflectEmotionQueue.Job job = queue.peek();
        if (job == null) { finishRun(0); return; }
        File recording = new File(new File(context.getFilesDir(), "recordings"), job.recordingName);
        try {
            if (!recording.isFile()) { queue.remove(job); io.execute(this::drain); return; }
            ReflectTranscriptStore.Snapshot snapshot = store.snapshot(recording);
            if (!matches(job, snapshot)) {
                queue.remove(job);
                queue.enqueue(recording, snapshot);
                io.execute(this::drain);
                return;
            }
            int offset = job.nextIndex;
            while (offset < snapshot.transcript.lines.size()
                    && snapshot.transcript.lines.get(offset).emotionProbabilities != null) offset++;
            if (offset > job.nextIndex) queue.advance(job, offset);
            if (offset >= snapshot.transcript.lines.size()) {
                queue.finish(job);
                io.execute(this::drain);
                return;
            }
            ArrayList<Utterance> lines = new ArrayList<>();
            for (int i = offset; i < snapshot.transcript.lines.size(); i++) {
                ReflectTranscript.Line line = snapshot.transcript.lines.get(i);
                lines.add(new Utterance(line.speakerId, line.startMs, line.endMs,
                        line.text, Collections.emptyList(), line.emotionProbabilities));
            }
            Batch started = new Batch(run, job, recording, offset);
            batch = started;
            main.post(() -> {
                if (started.cancelled || started.owner.stopped) return;
                started.task = ReflectEmotionEngine.analyze(context, Uri.fromFile(recording), lines,
                        new ReflectEmotionEngine.Listener() {
                    @Override public boolean onUtterance(int index, EmotionProbabilities scores) {
                        if (started.cancelled || started.owner.stopped) return false;
                        try {
                            int absolute = started.offset + index;
                            boolean current = scores == null
                                    ? matches(job, store.snapshot(recording))
                                    : store.applyEmotion(recording, job.revision, job.sourceLength,
                                            job.sourceModified, absolute, scores);
                            if (!current) {
                                // false has no engine terminal callback; retire this stale batch ourselves.
                                io.execute(() -> complete(started, Outcome.STALE));
                                return false;
                            }
                            queue.advance(job, absolute + 1);
                            return !started.cancelled && !started.owner.stopped;
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }
                    @Override public void onDone() { io.execute(() -> complete(started, Outcome.DONE)); }
                    @Override public void onYielded() { io.execute(() -> complete(started, Outcome.YIELDED)); }
                    @Override public void onError(Throwable error) {
                        Log.w("ReflectEmotions", "Local emotion analysis will retry", error);
                        io.execute(() -> complete(started, Outcome.FAILED));
                    }
                });
                if (started.cancelled || started.owner.stopped) started.task.cancel();
            });
        } catch (IOException | RuntimeException failure) {
            queue.fail(job);
            io.execute(this::drain);
        }
    }

    private void complete(Batch completed, Outcome outcome) {
        if (batch != completed || run != completed.owner) return;
        switch (outcome) {
            case DONE -> queue.finish(completed.job);
            case YIELDED -> queue.deferYield(completed.job);
            case FAILED -> queue.fail(completed.job);
            case STALE -> { queue.remove(completed.job); discover(completed.recording); }
        }
        batch = null;
        if (outcome == Outcome.YIELDED) finishRun(1000); else drain();
    }

    private static boolean matches(ReflectEmotionQueue.Job job, ReflectTranscriptStore.Snapshot snapshot) {
        return snapshot != null && job.revision.equals(snapshot.revision)
                && job.sourceLength == snapshot.sourceLength && job.sourceModified == snapshot.sourceModified;
    }

    private void finishRun(long minimumDelay) {
        Run finished = run;
        run = null;
        long delay = queue.nextDelayMs();
        main.post(() -> {
            if (finished != null && !finished.stopped) finished.completed.run();
            if (delay >= 0) schedule(Math.max(minimumDelay, delay));
        });
    }

    private void scheduleIfIdle() {
        if (run != null) return;
        long delay = queue.nextDelayMs();
        if (delay >= 0) main.post(() -> schedule(delay));
    }

    private void schedule(long delay) {
        // A discovery callback may have posted this before the OS started the current drain.
        // Replacing its JobInfo now would stop that running job.
        if (run != null) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, ReflectEmotionJobService.class))
                .setPersisted(true).setMinimumLatency(Math.max(1, delay)).build();
        if (scheduler.schedule(job) == JobScheduler.RESULT_FAILURE) {
            Log.w("ReflectEmotions", "JobScheduler rejected local emotion work");
        }
    }

    private enum Outcome { DONE, YIELDED, FAILED, STALE }
    private static final class Run {
        final Runnable completed;
        volatile boolean stopped;
        Run(Runnable completed) { this.completed = completed; }
    }
    private static final class Batch {
        final Run owner;
        final ReflectEmotionQueue.Job job;
        final File recording;
        final int offset;
        volatile boolean cancelled;
        volatile ReflectEmotionEngine.Task task;
        Batch(Run owner, ReflectEmotionQueue.Job job, File recording, int offset) {
            this.owner = owner; this.job = job; this.recording = recording; this.offset = offset;
        }
    }
}
