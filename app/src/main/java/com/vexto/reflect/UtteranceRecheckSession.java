package com.vexto.reflect;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import com.vexto.engine.ReflectEngine;
import com.vexto.engine.model.ProcessingMode;
import com.vexto.engine.model.ProcessingStats;
import com.vexto.engine.model.Stage;
import com.vexto.engine.model.Transcript;
import com.vexto.engine.model.TranscriptionOptions;
import com.vexto.engine.model.Utterance;
import com.vexto.engine.model.Word;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Качественный прогон короткого окна и публикация только успешной непустой замены. */
final class UtteranceRecheckSession {
    private static final String TAG = "UtteranceRecheck";
    private final Activity activity;
    private final ReflectTranscriptStore store;
    private final SpeakerProfiles profiles;
    private final java.util.concurrent.ExecutorService io;
    private ReflectEngine.Task task;
    private int attempt;
    private boolean starting;

    UtteranceRecheckSession(Activity activity, ReflectTranscriptStore store, SpeakerProfiles profiles,
                            java.util.concurrent.ExecutorService io) {
        this.activity = activity;
        this.store = store;
        this.profiles = profiles;
        this.io = io;
    }

    boolean active() { return task != null && task.isActive(); }

    void cancel() {
        starting = false;
        attempt++;
        ReflectEngine.Task running = task;
        task = null;
        if (running != null) running.cancel();
    }

    void start(File recording, ReflectTranscript transcript, ReflectTranscript.Line line, int generation, Host host) {
        if (recording == null || transcript == null || line == null || host == null) return;
        if (starting || active() || ReflectEngine.transcriptionBusy()) {
            host.message("Дождитесь окончания текущей обработки");
            return;
        }
        starting = true;
        int mine = ++attempt;
        host.shimmer(line);
        io.execute(() -> prepare(recording, transcript, line, generation, mine, host));
    }

    private void prepare(File recording, ReflectTranscript transcript, ReflectTranscript.Line line,
                         int generation, int mine, Host host) {
        File clip = null;
        try {
            File wav = store.decodedWav(recording);
            if (wav == null) {
                fail(generation, mine, host, "Не удалось взять исходный фрагмент. Текст оставлен.");
                return;
            }
            UtteranceRecheck.Window window = UtteranceRecheck.window(
                    line.startMs, line.endMs, ReflectEngine.wavDurationMs(wav));
            if (window == null) {
                fail(generation, mine, host, "Не удалось выделить фрагмент. Текст оставлен.");
                return;
            }
            clip = File.createTempFile("recheck-", ".wav", activity.getCacheDir());
            ReflectEngine.clipWav(wav, window.startMs, window.endMs, clip);
            File source = clip;
            activity.runOnUiThread(() -> {
                if (mine != attempt || !host.current(generation)) {
                    starting = false;
                    source.delete();
                    if (host.current(generation)) host.idle();
                    return;
                }
                task = ReflectEngine.transcribe(activity, Uri.fromFile(source),
                        new TranscriptionOptions(null, true, ProcessingMode.QUALITY),
                        new ReflectEngine.Listener() {
                            @Override public void onProgress(Stage stage, float fraction) {}

                            @Override public void onPartial(Transcript partial) {}

                            @Override public void onDone(Transcript result, ProcessingStats stats) {
                                starting = false;
                                task = null;
                                io.execute(() -> finish(recording, line, window, generation, mine, host,
                                        result, stats.getArtifactDirectory(), source));
                            }

                            @Override public void onError(Throwable error) {
                                starting = false;
                                task = null;
                                Log.w(TAG, "Recheck failed", error);
                                io.execute(() -> {
                                    source.delete();
                                    if (mine == attempt) {
                                        fail(generation, mine, host, "Не удалось перепроверить фрагмент. Текст оставлен.");
                                    }
                                });
                            }
                        });
            });
        } catch (RuntimeException | IOException failure) {
            Log.w(TAG, "Could not prepare recheck clip", failure);
            starting = false;
            if (clip != null) clip.delete();
            fail(generation, mine, host, "Не удалось перепроверить фрагмент. Текст оставлен.");
        }
    }

    private void finish(File recording, ReflectTranscript.Line line, UtteranceRecheck.Window window,
                        int generation, int mine, Host host, Transcript result, String artifacts, File clip) {
        try {
            if (mine != attempt) return;
            ReflectTranscript fresh = store.load(recording);
            if (fresh == null || !contains(fresh, line)) {
                fail(generation, mine, host, "Текст уже изменился. Замена не выполнена.");
                return;
            }
            List<UtteranceRecheck.Piece> pieces = pieces(result, window.startMs);
            ReflectTranscript updated = UtteranceRecheck.apply(fresh, line, window.startMs, window.endMs, pieces);
            if (updated == null) {
                fail(generation, mine, host, "Фрагмент распознан пустым. Текст оставлен.");
                return;
            }
            if (updated == fresh) {
                activity.runOnUiThread(() -> {
                    if (mine == attempt && host.current(generation)) host.idle();
                });
                return;
            }
            store.save(recording, updated);
            try { profiles.retainAcrossRefinement(recording, fresh, updated); }
            catch (IOException | RuntimeException links) { Log.w(TAG, "Could not keep speaker links", links); }
            try { profiles.assign(recording, updated); }
            catch (IOException | RuntimeException links) { Log.w(TAG, "Could not refresh speaker links", links); }
            try { store.deleteSummary(recording); }
            catch (IOException ignored) { /* Резюме относилось к прежнему тексту. */ }
            activity.runOnUiThread(() -> {
                if (mine != attempt || !host.current(generation)) return;
                host.show(updated);
            });
        } catch (RuntimeException | IOException failure) {
            Log.w(TAG, "Could not publish recheck", failure);
            fail(generation, mine, host, "Не удалось сохранить перепроверку. Текст оставлен.");
        } finally {
            clip.delete();
            deleteJob(artifacts);
        }
    }

    private static List<UtteranceRecheck.Piece> pieces(Transcript result, int offsetMs) {
        List<UtteranceRecheck.Piece> pieces = new ArrayList<>();
        if (result == null) return pieces;
        for (Utterance utterance : result.getUtterances()) {
            if (utterance.getText() == null || utterance.getText().trim().isEmpty()) continue;
            try {
                List<UtteranceRecheck.TimedWord> words = new ArrayList<>();
                for (Word word : utterance.getWords()) {
                    if (word.getText() == null || word.getText().isBlank()) continue;
                    words.add(new UtteranceRecheck.TimedWord(
                            Math.toIntExact(Math.addExact(word.getStartMs(), offsetMs)),
                            Math.toIntExact(Math.addExact(word.getEndMs(), offsetMs)),
                            word.getText()));
                }
                pieces.add(new UtteranceRecheck.Piece(
                        Math.toIntExact(Math.addExact(utterance.getStartMs(), offsetMs)),
                        Math.toIntExact(Math.addExact(utterance.getEndMs(), offsetMs)),
                        utterance.getText(), utterance.getEmotionProbabilities(), words, utterance.getOverlapDetected()));
            } catch (ArithmeticException overflow) {
                return List.of();
            }
        }
        pieces.sort(Comparator.comparingInt(piece -> piece.startMs));
        return pieces;
    }

    private static boolean contains(ReflectTranscript transcript, ReflectTranscript.Line selected) {
        for (ReflectTranscript.Line line : transcript.lines) {
            if (line.startMs == selected.startMs && line.endMs == selected.endMs
                    && line.speakerId.equals(selected.speakerId) && line.text.equals(selected.text)) return true;
        }
        return false;
    }

    private void fail(int generation, int mine, Host host, String text) {
        if (mine != attempt) return;
        starting = false;
        activity.runOnUiThread(() -> {
            if (mine != attempt || !host.current(generation)) return;
            host.idle();
            host.message(text);
        });
    }

    private void deleteJob(String artifacts) {
        if (artifacts == null || artifacts.isBlank()) return;
        try {
            File root = new File(activity.getFilesDir(), "inference/jobs").getCanonicalFile();
            File job = new File(artifacts).getCanonicalFile();
            if (!root.equals(job.getParentFile()) || !job.isDirectory()) return;
            deleteTree(job);
        } catch (IOException ignored) { /* Временный прогон можно оставить до очистки хранилища. */ }
    }

    private static void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        Files.deleteIfExists(file.toPath());
    }

    interface Host {
        boolean current(int generation);
        void shimmer(ReflectTranscript.Line line);
        void idle();
        void show(ReflectTranscript transcript);
        void message(String text);
    }
}
