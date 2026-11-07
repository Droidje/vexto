package com.vexto.reflect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.vexto.engine.ReflectEngine;
import com.vexto.engine.models.OfflineModelPackException;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Idle work is scoped to the open app; the conversation database owns the one-time result. */
final class ReflectConversationTitles {
    private static final long IDLE_DELAY_MS = 1_800;
    private final Context context;
    private final ReflectConversationStore conversations;
    private final ReflectTranscriptStore transcripts;
    private final Executor io;
    private final Consumer<File> updated;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pump = this::runNext;
    private List<ReflectCatalog.Conversation> catalog = List.of();
    private ReflectEngine.Task task;
    private boolean scheduled, preparing, summaryActive, deferred, destroyed;
    private volatile boolean foreground, backendBusy;
    private int attempt;

    ReflectConversationTitles(Context context, ReflectConversationStore conversations, ReflectTranscriptStore transcripts,
                              Executor io, Consumer<File> updated) {
        this.context = context.getApplicationContext();
        this.conversations = conversations;
        this.transcripts = transcripts;
        this.io = io;
        this.updated = updated;
    }

    void catalog(List<ReflectCatalog.Conversation> value) {
        catalog = value;
        schedule();
    }

    void foreground(boolean value) {
        foreground = value;
        if (value) {
            deferred = false;
            schedule();
        } else interrupt();
    }

    void backendBusy(boolean value) {
        backendBusy = value;
        if (value) interrupt();
        else schedule();
    }

    void prioritizeSummary() {
        summaryActive = true;
        interrupt();
    }

    void summaryFinished() {
        summaryActive = false;
        schedule();
    }

    void destroy() {
        destroyed = true;
        foreground(false);
    }

    BooleanSupplier warmEligibility(File recording) {
        return () -> canWork() && conversations.needsTitle(recording);
    }

    void acceptWarm(File recording, String fingerprint, String title) {
        if (!destroyed) persist(recording, fingerprint, title);
    }

    private void interrupt() {
        handler.removeCallbacks(pump);
        scheduled = false;
        ++attempt;
        preparing = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private boolean canWork() { return foreground && !backendBusy; }

    private void schedule() {
        if (!canWork() || summaryActive || deferred || preparing || task != null || destroyed || scheduled) return;
        if (catalog.stream().noneMatch(item -> item.needsTitle)) return;
        scheduled = true;
        handler.postDelayed(pump, IDLE_DELAY_MS);
    }

    private void runNext() {
        scheduled = false;
        if (!canWork() || summaryActive || deferred || destroyed) return;
        preparing = true;
        int current = ++attempt;
        var candidates = catalog;
        io.execute(() -> {
            Candidate candidate = null;
            for (var item : candidates) {
                if (!item.needsTitle) continue;
                File recording = item.recording.file;
                try {
                    if (!conversations.needsTitle(recording)) continue;
                    ReflectTranscript transcript = transcripts.load(recording);
                    if (transcript == null || transcript.lines.isEmpty()) continue;
                    candidate = new Candidate(recording, ReflectSummaryController.sourceOf(transcript));
                    break;
                } catch (IOException | RuntimeException ignored) {
                    // An unreadable conversation must not block the rest of the catalog.
                }
            }
            Candidate next = candidate;
            handler.post(() -> {
                if (destroyed || current != attempt) return;
                preparing = false;
                if (next != null && canWork()) start(next, current);
            });
        });
    }

    private void start(Candidate candidate, int current) {
        task = ReflectEngine.tryGenerateTitle(context, candidate.source.text(), warmEligibility(candidate.recording),
                new ReflectEngine.TitleListener() {
                    @Override public void onDone(String title) {
                        if (current != attempt) return;
                        task = null;
                        persist(candidate.recording, candidate.source.fingerprint(), title);
                    }

                    @Override public void onBusy() {
                        if (current != attempt) return;
                        task = null;
                        schedule();
                    }

                    @Override public void onError(Throwable failure) {
                        if (current != attempt) return;
                        task = null;
                        if (failure instanceof OfflineModelPackException) deferred = true;
                        else persist(candidate.recording, candidate.source.fingerprint(), null);
                    }
                });
    }

    private void persist(File recording, String fingerprint, String title) {
        io.execute(() -> {
            try {
                transcripts.withCurrentText(recording, fingerprint, () -> conversations.completeTitle(recording, title));
            } catch (IOException | RuntimeException failure) {
                // Keep the numbered title and defer a storage failure until the app is resumed again.
                handler.post(() -> deferred = true);
            }
            handler.post(() -> { if (!destroyed) updated.accept(recording); });
        });
    }

    private record Candidate(File recording, ReflectSummaryController.Source source) {}
}
