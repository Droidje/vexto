package com.vexto.reflect;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable local changes and interactive calls for one explicitly configured server. */
final class ReflectBackendSync implements AutoCloseable {
    record Speaker(String id, String name) {}
    record SpeakerLinks(Map<String, String> links, String revision) {}

    private static final String TAG = "ReflectBackendSync";
    private static final Object OUTBOX_LOCK = new Object();
    private static final Object CACHE_LOCK = new Object();
    // Shared across Activity recreation so writes to the same recording stay ordered.
    private static final ScheduledExecutorService UPLOADS = Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor();
    private static final int FIRST_RETRY_SECONDS = 2, MAX_RETRY_SECONDS = 120;
    private static volatile ReflectBackendSync current;

    private final SharedPreferences settings;
    private final SharedPreferences outbox;
    private final ReflectTranscriptStore transcripts;
    private final File recordings;
    private final File cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectivityManager connectivity;
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network available) { retry(); }
    };
    private boolean networkRegistered;
    private volatile ReflectBackendClient.Config config;
    private Consumer<File> generatedConflict;
    private volatile boolean closed;
    private ScheduledFuture<?> scheduled;
    private long scheduleId;
    private int backoff = FIRST_RETRY_SECONDS;

    ReflectBackendSync(Context context) {
        Context app = context.getApplicationContext();
        settings = app.getSharedPreferences("reflect_backend", Context.MODE_PRIVATE);
        outbox = app.getSharedPreferences("reflect_backend_outbox", Context.MODE_PRIVATE);
        recordings = new File(app.getFilesDir(), "recordings");
        cache = new File(app.getCacheDir(), "backend_transcripts");
        transcripts = new ReflectTranscriptStore(app.getFilesDir());
        connectivity = app.getSystemService(ConnectivityManager.class);
        try {
            String url = settings.getString("url", "");
            if (!url.isEmpty()) config = new ReflectBackendClient.Config(url, settings.getString("token", ""));
        } catch (IllegalArgumentException ignored) {
            // A previous setting may no longer satisfy the current transport policy.
        }
        if (connectivity != null) {
            try {
                connectivity.registerDefaultNetworkCallback(network);
                networkRegistered = true;
            } catch (SecurityException unavailable) {
                // The outbox still retries on the timer and Activity resume.
            }
        }
        current = this;
        retry();
    }

    boolean enabled() { return config != null; }

    String syncScope() {
        var selected = config;
        return selected == null ? null : selected.scope;
    }

    void onGeneratedConflict(Consumer<File> listener) { generatedConflict = listener; }

    String configuredUrl() {
        var selected = config;
        return selected == null ? "" : selected.url;
    }

    synchronized void configure(String url, String token) {
        ReflectBackendClient.Config next = null;
        if (!url.trim().isEmpty() || !token.trim().isEmpty()) {
            String normalized = ReflectBackendClient.Config.normalizeUrl(url);
            var previous = config;
            if (token.trim().isEmpty() && previous != null && previous.url.equals(normalized)) token = previous.token;
            next = new ReflectBackendClient.Config(normalized, token);
        }
        SharedPreferences.Editor editor = settings.edit();
        if (next == null) editor.clear();
        else editor.putString("url", next.url).putString("token", next.token);
        if (!editor.commit()) throw new IllegalArgumentException("Не удалось сохранить подключение");
        config = next;
        cancelScheduled();
        backoff = FIRST_RETRY_SECONDS;
        retry();
    }

    boolean onSaved(File audio, String title, int durationMs) {
        return enqueue(config, audio, title, durationMs, false, false, false);
    }

    boolean onRenamed(File audio, String title, int durationMs) {
        return enqueue(config, audio, title, durationMs, false, false, true);
    }

    boolean onImported(File audio, String title, int durationMs, ReflectTranscript imported) {
        return imported != null && enqueue(config, audio, title, durationMs, true, false, false);
    }

    boolean onGenerated(File audio, String title, int durationMs, String scope) {
        var selected = config;
        return selected != null && selected.scope.equals(scope)
                && enqueue(selected, audio, title, durationMs, true, true, false);
    }

    int pendingCount() {
        var selected = config;
        if (selected == null) return 0;
        int count = 0;
        for (String key : outbox.getAll().keySet()) {
            if (!key.startsWith(selected.scope + "/")) continue;
            Job job = load(key);
            if (job != null && job.pending()) count++;
        }
        return count;
    }

    private boolean enqueue(ReflectBackendClient.Config selected, File audio, String title, int durationMs,
                            boolean transcriptChanged, boolean createOnly, boolean requirePrevious) {
        if (selected == null || !isSaved(audio) || durationMs <= 0) return false;
        String key = key(selected, audio);
        synchronized (OUTBOX_LOCK) {
            Job previous = load(key);
            if (previous == null && requirePrevious) return false;
            String edit = UUID.randomUUID().toString();
            Job next = new Job(audio.getName(), title, durationMs, previous != null && previous.audioSent, edit,
                    transcriptChanged ? edit : previous == null ? "" : previous.transcriptEdit,
                    transcriptChanged ? edit : previous == null ? "" : previous.lastImport,
                    transcriptChanged ? createOnly : previous != null && previous.transcriptCreateOnly);
            if (!persist(key, next)) return false;
        }
        if (transcriptChanged) {
            synchronized (CACHE_LOCK) { transcriptCache(selected, id(audio)).delete(); }
        }
        var activeInstance = current;
        if (activeInstance != null) activeInstance.retry();
        return true;
    }

    void retry() { schedule(0); }

    private synchronized void schedule(int seconds) {
        if (closed || config == null) return;
        if (scheduled != null && seconds > 0) return;
        cancelScheduled();
        long version = scheduleId;
        scheduled = UPLOADS.schedule(() -> {
            var selected = startScheduled(version);
            if (selected != null) flush(selected);
        }, seconds, TimeUnit.SECONDS);
    }

    private synchronized ReflectBackendClient.Config startScheduled(long version) {
        if (version != scheduleId || closed) return null;
        scheduled = null;
        return config;
    }

    private void cancelScheduled() {
        scheduleId++;
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
    }

    private void flush(ReflectBackendClient.Config selected) {
        var client = new ReflectBackendClient(selected);
        boolean temporaryFailure = false;
        for (String key : outbox.getAll().keySet()) {
            if (!active(selected)) return;
            if (!key.startsWith(selected.scope + "/")) continue;
            Job job = load(key);
            if (job == null || !job.pending()) continue;
            File audio = new File(recordings, job.filename);
            if (!isSaved(audio)) continue;
            String id = id(audio);
            try {
                if (!job.audioSent || !job.metadataEdit.isEmpty()) {
                    RecordingMetadata metadata = RecordingMetadata.read(audio);
                    if (!active(selected)) return;
                    if (!job.audioSent) {
                        client.uploadAudio(id, audio, metadata.mime());
                        acknowledge(key, job, true, false, false);
                    }
                    if (!job.metadataEdit.isEmpty() && active(selected)) {
                        client.uploadMetadata(id, audio, job.title, job.durationMs, metadata);
                        acknowledge(key, job, false, true, false);
                    }
                }
                if (!job.transcriptEdit.isEmpty() && active(selected)) {
                    ReflectTranscript transcript = transcripts.load(audio);
                    if (transcript != null) {
                        client.uploadTranscript(id, transcript, job.transcriptCreateOnly);
                        acknowledge(key, job, false, false, true);
                    }
                }
            } catch (ReflectBackendClient.HttpError failure) {
                if (job.transcriptCreateOnly && failure.status == 412) {
                    boolean cleared = acknowledge(key, job, false, false, true);
                    if (cleared) main.post(() -> {
                        if (active(selected) && canReadTranscript(selected, key, job.lastImport)
                                && generatedConflict != null) generatedConflict.accept(audio);
                    });
                    else temporaryFailure = true;
                } else temporaryFailure |= failure.transientFailure();
            } catch (IOException networkOrFile) {
                temporaryFailure = true;
            } catch (RuntimeException invalidFile) {
                Log.w(TAG, "Cannot synchronize a local recording");
            }
        }
        afterFlush(selected, temporaryFailure);
    }

    private synchronized void afterFlush(ReflectBackendClient.Config selected, boolean temporaryFailure) {
        if (!active(selected)) return;
        if (temporaryFailure) {
            schedule(backoff);
            backoff = Math.min(MAX_RETRY_SECONDS, backoff * 2);
        } else {
            backoff = FIRST_RETRY_SECONDS;
        }
    }

    private boolean acknowledge(String key, Job sent, boolean audio, boolean metadata, boolean transcript) {
        synchronized (OUTBOX_LOCK) {
            Job current = load(key);
            if (current == null) return false;
            boolean clearTranscript = transcript && current.transcriptEdit.equals(sent.transcriptEdit);
            return persist(key, new Job(current.filename, current.title, current.durationMs, current.audioSent || audio,
                    metadata && current.metadataEdit.equals(sent.metadataEdit) ? "" : current.metadataEdit,
                    clearTranscript ? "" : current.transcriptEdit, current.lastImport,
                    !clearTranscript && current.transcriptCreateOnly)) && clearTranscript;
        }
    }

    void pullTranscript(File audio, Consumer<ReflectBackendClient.TranscriptResponse> result) {
        var selected = config;
        if (selected == null || !isSaved(audio)) return;
        String id = id(audio);
        String key = key(selected, audio);
        REQUESTS.execute(() -> {
            Job before = load(key);
            String lastImport = before == null ? "" : before.lastImport;
            if (!canReadTranscript(selected, key, lastImport)) return;
            ReflectBackendClient.TranscriptResponse remote;
            try {
                remote = new ReflectBackendClient(selected).transcript(id);
                synchronized (CACHE_LOCK) {
                    if (!canReadTranscript(selected, key, lastImport)) return;
                    if (remote == null) transcriptCache(selected, id).delete();
                    else cacheTranscript(selected, id, remote.value());
                }
            } catch (ReflectBackendClient.HttpError error) {
                remote = error.transientFailure() ? offlineTranscript(selected, id) : null;
            } catch (IOException unavailable) {
                remote = offlineTranscript(selected, id);
            }
            ReflectBackendClient.TranscriptResponse received = remote;
            if (received != null) {
                main.post(() -> {
                    if (canReadTranscript(selected, key, lastImport)) result.accept(received);
                });
            }
        });
    }

    private ReflectBackendClient.TranscriptResponse offlineTranscript(ReflectBackendClient.Config selected, String id) {
        ReflectTranscript cached = cachedTranscript(selected, id);
        return cached == null ? null : new ReflectBackendClient.TranscriptResponse(cached, null);
    }

    ReflectTranscript cachedTranscript(File audio) {
        var selected = config;
        if (selected == null || !isSaved(audio)) return null;
        String key = key(selected, audio);
        Job before = load(key);
        String lastImport = before == null ? "" : before.lastImport;
        if (!canReadTranscript(selected, key, lastImport)) return null;
        ReflectTranscript transcript = cachedTranscript(selected, id(audio));
        return canReadTranscript(selected, key, lastImport) ? transcript : null;
    }

    private boolean canReadTranscript(ReflectBackendClient.Config selected, String key, String lastImport) {
        Job latest = load(key);
        return active(selected) && (latest == null ? lastImport.isEmpty()
                : latest.transcriptEdit.isEmpty() && latest.lastImport.equals(lastImport));
    }

    void speakers(Consumer<List<Speaker>> result, Consumer<String> failure) {
        interactive(client -> {
            JSONArray values = client.speakers().getJSONArray("speakers");
            List<Speaker> speakers = new ArrayList<>(values.length());
            for (int i = 0; i < values.length(); i++) speakers.add(speaker(values.getJSONObject(i)));
            return speakers;
        }, result, failure);
    }

    void createSpeaker(String name, Consumer<Speaker> result, Consumer<String> failure) {
        interactive(client -> speaker(client.createSpeaker(name)), result, failure);
    }

    void renameSpeaker(String id, String name, Consumer<Speaker> result, Consumer<String> failure) {
        interactive(client -> speaker(client.renameSpeaker(id, name)), result, failure);
    }

    void deleteSpeaker(String id, Runnable result, Consumer<String> failure) {
        interactive(client -> { client.deleteSpeaker(id); return null; }, unused -> result.run(), failure);
    }

    void speakerLinks(File audio, ReflectTranscript expected, String revision, Consumer<SpeakerLinks> result,
                      Consumer<String> failure) {
        if (!isSaved(audio)) {
            failure.accept("Сначала сохраните запись");
            return;
        }
        retry();
        interactive(UPLOADS, client -> {
            String recording = id(audio);
            String version = revision;
            if (version == null) {
                var latest = client.transcript(recording);
                if (latest == null) throw new ReflectBackendClient.HttpError(404);
                if (latest.revision() == null || !expected.sameLines(latest.value())) {
                    throw new ReflectBackendClient.HttpError(409);
                }
                version = latest.revision();
            }
            var response = client.speakerLinks(recording, version);
            if (!version.equals(response.revision())) throw new ReflectBackendClient.HttpError(409);
            JSONObject values = response.value().getJSONObject("links");
            Map<String, String> links = new LinkedHashMap<>();
            var keys = values.keys();
            while (keys.hasNext()) {
                String label = keys.next();
                links.put(label, values.getString(label));
            }
            return new SpeakerLinks(links, response.revision());
        }, result, failure);
    }

    void linkSpeaker(File audio, String localId, String speakerId, String revision, Runnable result,
                     Consumer<String> failure) {
        if (!isSaved(audio)) {
            failure.accept("Сначала сохраните запись");
            return;
        }
        retry();
        interactive(UPLOADS, client -> {
            client.linkSpeaker(id(audio), localId, speakerId, revision);
            return null;
        }, unused -> result.run(), failure);
    }

    private <T> void interactive(Request<T> request, Consumer<T> result, Consumer<String> failure) {
        interactive(REQUESTS, request, result, failure);
    }

    private <T> void interactive(ExecutorService executor, Request<T> request, Consumer<T> result,
                                 Consumer<String> failure) {
        var selected = config;
        if (closed) return;
        if (selected == null) {
            failure.accept("Подключите сервер в настройках");
            return;
        }
        executor.execute(() -> {
            if (!active(selected)) return;
            try {
                T value = request.run(new ReflectBackendClient(selected));
                main.post(() -> { if (active(selected)) result.accept(value); });
            } catch (ReflectBackendClient.HttpError rejected) {
                main.post(() -> { if (active(selected)) failure.accept(errorMessage(rejected.status)); });
            } catch (IOException | JSONException unavailable) {
                main.post(() -> { if (active(selected)) failure.accept("Сервер недоступен. Попробуйте ещё раз"); });
            }
        });
    }

    private static String errorMessage(int status) {
        return switch (status) {
            case 401, 403 -> "Сервер отклонил ключ доступа";
            case 404 -> "Запись, расшифровка или спикер ещё недоступны на сервере";
            case 409 -> "Изменение конфликтует с данными на сервере";
            case 413 -> "Размер файла превышает лимит сервера";
            case 422 -> "Сервер отклонил данные";
            default -> "Сервер недоступен. Попробуйте ещё раз";
        };
    }

    private static Speaker speaker(JSONObject value) throws JSONException {
        return new Speaker(value.getString("id"), value.getString("name"));
    }

    private AtomicFile transcriptCache(ReflectBackendClient.Config selected, String id) {
        return new AtomicFile(new File(cache, selected.scope + "-" + id + ".json"));
    }

    private void cacheTranscript(ReflectBackendClient.Config selected, String id, ReflectTranscript transcript) {
        AtomicFile file = transcriptCache(selected, id);
        java.io.FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(transcript.encode());
            file.finishWrite(output);
        } catch (IOException unavailable) {
            if (output != null) file.failWrite(output);
        }
    }

    private ReflectTranscript cachedTranscript(ReflectBackendClient.Config selected, String id) {
        synchronized (CACHE_LOCK) {
            try (var input = transcriptCache(selected, id).openRead()) {
                return ReflectTranscript.parse(input);
            } catch (IOException invalidOrMissing) {
                return null;
            }
        }
    }

    private boolean active(ReflectBackendClient.Config selected) { return !closed && config == selected; }

    private boolean isSaved(File audio) { return audio != null && recordings.equals(audio.getParentFile()) && audio.isFile(); }

    private static String key(ReflectBackendClient.Config config, File audio) { return config.scope + "/" + id(audio); }

    private static String id(File audio) {
        String filename = audio.getName();
        int dot = filename.lastIndexOf('.');
        String stem = dot < 0 ? filename : filename.substring(0, dot);
        try {
            return UUID.fromString(stem).toString();
        } catch (IllegalArgumentException legacy) {
            return UUID.nameUUIDFromBytes(filename.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private Job load(String key) {
        String raw = outbox.getString(key, null);
        if (raw == null) return null;
        try {
            JSONObject job = new JSONObject(raw);
            return new Job(job.getString("filename"), job.getString("title"), job.getInt("duration"),
                    job.getBoolean("audio"), job.getString("metadata"), job.getString("transcript"),
                    job.getString("lastImport"), job.optBoolean("transcriptCreateOnly"));
        } catch (JSONException invalid) {
            return null;
        }
    }

    private boolean persist(String key, Job job) {
        try {
            String value = new JSONObject().put("filename", job.filename).put("title", job.title)
                    .put("duration", job.durationMs).put("audio", job.audioSent)
                    .put("metadata", job.metadataEdit).put("transcript", job.transcriptEdit)
                    .put("lastImport", job.lastImport).put("transcriptCreateOnly", job.transcriptCreateOnly)
                    .toString();
            if (outbox.edit().putString(key, value).commit()) return true;
            Log.w(TAG, "Cannot save synchronization state");
        } catch (JSONException invalid) {
            Log.w(TAG, "Invalid synchronization state");
        }
        return false;
    }

    @Override public synchronized void close() {
        closed = true;
        if (current == this) current = null;
        cancelScheduled();
        if (networkRegistered) {
            connectivity.unregisterNetworkCallback(network);
            networkRegistered = false;
        }
    }

    private record Job(String filename, String title, int durationMs, boolean audioSent, String metadataEdit,
                       String transcriptEdit, String lastImport, boolean transcriptCreateOnly) {
        boolean pending() { return !audioSent || !metadataEdit.isEmpty() || !transcriptEdit.isEmpty(); }
    }

    private interface Request<T> { T run(ReflectBackendClient client) throws IOException, JSONException; }
}
