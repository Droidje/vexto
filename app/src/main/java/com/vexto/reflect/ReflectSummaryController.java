package com.vexto.reflect;

import android.app.Activity;
import android.util.Log;
import com.vexto.engine.ReflectEngine;
import com.vexto.engine.runtime.NativeProcessException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns one local generation, while the conversation view may come and go. */
final class ReflectSummaryController {
    private static final String SUMMARY_DOMAIN = "vexto-summary-gist-v3\n";
    private final Activity activity;
    private final ReflectTranscriptStore store;
    private final ReflectConversationTitles titles;
    private final Executor io;
    private final Consumer<ReflectSummaryState> display;

    private File recording;
    private ReflectTranscript transcript;
    private Source source;
    private boolean opened, reading, requested, offerCached, destroyed;
    private int revision;
    private ReflectSummaryState state;
    private Generation active;

    ReflectSummaryController(Activity activity, ReflectTranscriptStore store, ReflectConversationTitles titles, Executor io,
                             Consumer<ReflectSummaryState> display) {
        this.activity = activity;
        this.store = store;
        this.titles = titles;
        this.io = io;
        this.display = display;
    }

    void content(File file, ReflectTranscript value) {
        if (Objects.equals(recording, file) && transcript == value) return;
        boolean newConversation = !Objects.equals(recording, file);
        recording = file;
        transcript = value;
        source = null;
        state = null;
        if (newConversation) {
            opened = requested = false;
            offerCached = file != null;
        }
        int current = ++revision;
        reading = file != null && value != null && !value.lines.isEmpty();
        if (!reading) {
            opened = requested = false;
            publish();
            return;
        }
        if (opened) state = busy("Проверяю сохранённое резюме…", -1);
        publish();
        io.execute(() -> {
            Source prepared = sourceOf(value);
            String saved;
            try { saved = store.loadSummary(file, prepared.fingerprint()); }
            catch (IOException | RuntimeException unavailable) { saved = null; }
            String cached = saved;
            activity.runOnUiThread(() -> {
                if (destroyed || current != revision) return;
                reading = false;
                source = prepared;
                if (active != null && active.matches(file, prepared)) {
                    state = active.state;
                    opened |= offerCached;
                } else if (cached != null) {
                    state = ready(cached);
                    opened |= offerCached;
                } else if (requested) {
                    start();
                } else if (opened) {
                    state = failure("Расшифровка изменилась. Можно создать новое резюме.");
                }
                requested = offerCached = false;
                publish();
            });
        });
    }

    void toggle() {
        if (recording == null || transcript == null || transcript.lines.isEmpty()) return;
        offerCached = false;
        opened = !opened;
        if (!opened) requested = false;
        else if (reading) {
            requested = true;
            state = busy("Проверяю сохранённое резюме…", -1);
        } else if (state == null) {
            start();
        }
        publish();
    }

    void retry() {
        if (!opened || reading) return;
        start();
        publish();
    }

    private void start() {
        if (recording == null || source == null) return;
        if (active != null) {
            state = active.matches(recording, source)
                    ? active.state : failure("Резюме другого разговора ещё готовится.");
            return;
        }
        Generation job = new Generation(recording, source);
        active = job;
        state = job.state;
        titles.prioritizeSummary();
        try {
            job.task = ReflectEngine.summarize(activity.getApplicationContext(), source.text(),
                    titles.warmEligibility(job.recording), new ReflectEngine.SummaryWithTitleListener() {
                @Override public void onProgress(int done, int total, long remainingMs) {
                    if (active != job || destroyed) return;
                    int percent = total <= 0 ? -1 : Math.min(99, Math.round(100f * done / total));
                    String step = total <= 0 ? "Готовлю локальную модель…"
                            : "Шаг " + Math.min(done + 1, total) + " из " + total;
                    if (remainingMs >= 0) step += " · осталось около " + eta(remainingMs);
                    job.state = busy(step, percent);
                    show(job);
                }

                @Override public void onDone(String result) {
                    if (active != job || destroyed) return;
                    job.state = busy("Сохраняю резюме…", 99);
                    show(job);
                    io.execute(() -> {
                        boolean persisted;
                        try { persisted = store.saveSummary(job.recording, job.source.fingerprint(), result); }
                        catch (IOException | RuntimeException unavailable) { persisted = false; }
                        boolean saved = persisted;
                        activity.runOnUiThread(() -> finish(job, saved ? ready(result)
                                : failure("Не удалось сохранить резюме. Повторите попытку.")));
                    });
                }

                @Override public void onTitle(String title) {
                    titles.acceptWarm(job.recording, job.source.fingerprint(), title);
                }

                @Override public void onError(Throwable error) {
                    logFailure(error);
                    finish(job, failure("Не удалось создать резюме на устройстве. Повторите попытку."));
                }
            });
        } catch (RuntimeException unavailable) {
            logFailure(unavailable);
            finish(job, failure("Не удалось запустить локальную модель. Повторите попытку."));
        }
    }

    /** Never include exception messages: native diagnostics may contain local paths or input. */
    private static void logFailure(Throwable error) {
        StringBuilder diagnostic = new StringBuilder("Summary failed");
        for (int depth = 0; error != null && depth < 5; depth++, error = error.getCause()) {
            diagnostic.append(" / ").append(error.getClass().getSimpleName());
            if (error instanceof NativeProcessException nativeError) {
                diagnostic.append(':').append(nativeError.getReason());
                if (nativeError.getExitCode() != null) diagnostic.append(':').append(nativeError.getExitCode());
            }
            // Only fixed error identifiers are allowed; never log the arbitrary message.
            String code = switch (Objects.toString(error.getMessage(), "")) {
                case "Invalid model response size" -> "OUTPUT_SIZE";
                case "Empty model response" -> "EMPTY_OUTPUT";
                case "QWEN_CONTEXT_BUDGET_EXCEEDED" -> "CONTEXT_BUDGET";
                case "Cannot initialize packaged Qwen model" -> "MODEL_INIT";
                case "Cannot initialize Qwen context" -> "CONTEXT_INIT";
                case "Cannot initialize constrained Qwen sampler" -> "SAMPLER_INIT";
                case "Qwen prompt evaluation failed" -> "PROMPT_EVAL";
                case "Qwen output evaluation failed" -> "OUTPUT_EVAL";
                default -> null;
            };
            if (code != null) diagnostic.append(':').append(code);
        }
        Log.w("VextoSummary", diagnostic.toString());
    }

    void cancel(File recording) {
        if (active == null || !active.recording.equals(recording)) return;
        if (active.task != null) active.task.cancel();
        active = null;
        titles.summaryFinished();
    }

    void destroy() {
        destroyed = true;
        if (active != null && active.task != null) active.task.cancel();
        active = null;
    }

    private void show(Generation job) {
        if (!job.matches(recording, source)) return;
        state = job.state;
        publish();
    }

    private void finish(Generation job, ReflectSummaryState result) {
        if (destroyed || active != job) return;
        active = null;
        titles.summaryFinished();
        job.state = result;
        show(job);
    }

    private void publish() { display.accept(opened ? state : null); }

    private static ReflectSummaryState ready(String value) {
        return new ReflectSummaryState(value, null, 100, false, false);
    }

    private static ReflectSummaryState busy(String text, int progress) {
        return new ReflectSummaryState(null, text, progress, true, false);
    }

    private static ReflectSummaryState failure(String text) {
        return new ReflectSummaryState(null, text, 0, false, true);
    }

    private static String eta(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        if (seconds < 60) return seconds + " с";
        return String.format(Locale.ROOT, "%d мин %02d с", seconds / 60, seconds % 60);
    }

    record Source(String text, String fingerprint) {}

    static Source sourceOf(ReflectTranscript transcript) {
        StringBuilder text = new StringBuilder();
        var speakers = new HashMap<String, Integer>();
        for (var line : transcript.lines) {
            int speaker = speakers.computeIfAbsent(line.speakerId, unused -> speakers.size() + 1);
            text.append("Участник ").append(speaker).append(": ")
                    .append(line.text.replace('\n', ' ').replace('\r', ' ')).append('\n');
        }
        String input = text.toString();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((SUMMARY_DOMAIN + input).getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                hex[i * 2] = Character.forDigit((hash[i] >>> 4) & 15, 16);
                hex[i * 2 + 1] = Character.forDigit(hash[i] & 15, 16);
            }
            return new Source(input, new String(hex));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class Generation {
        final File recording;
        final Source source;
        ReflectEngine.Task task;
        ReflectSummaryState state = busy("Готовлю локальную модель…", -1);

        Generation(File recording, Source source) {
            this.recording = recording;
            this.source = source;
        }

        boolean matches(File file, Source value) {
            return recording.equals(file) && value != null && source.fingerprint().equals(value.fingerprint());
        }
    }
}
