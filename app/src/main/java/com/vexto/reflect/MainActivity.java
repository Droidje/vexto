package com.vexto.reflect;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import com.vexto.engine.ReflectEngine;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import static com.vexto.reflect.ReflectView.Screen.*;

public final class MainActivity extends Activity implements ReflectView.Actions {
    private static final int MICROPHONE_PERMISSION = 1, PICK_AUDIO = 2, CALL_CAPTURE = 41;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private final RecordingSession session = new RecordingSession();
    private ReflectView view;
    private ReflectPreferences preferences;
    private ReflectTranscriptStore transcriptStore;
    private ReflectConversationStore conversationStore;
    private ReflectConversationTitles titles;
    private ReflectTranscriptController transcripts;
    private SpeakerProfiles profiles;
    private SpeakerProfilesUi profilesUi;
    private ReflectEngine.Task reviewWaveform;
    private File audio;
    private int duration;
    private MediaPlayer player;
    private int inFlightSeek = -1, seekTarget = -1;
    private AlertDialog recordingExit;
    private boolean importing;
    private boolean callMode, projectionPending;
    private final CallRecordingService.Observer callObserver = new CallRecordingService.Observer() {
        @Override public void onState(CallRecordingService.State state) {
            if (isDestroyed() || !state.active()) return;
            callMode = true;
            view.callMode(true);
            view.updateRecording(state.elapsedMs(), state.peakAmplitude());
            view.show(state.paused() ? PAUSED : RECORDING);
            handler.removeCallbacks(heartbeat);
            if (!state.paused()) handler.post(heartbeat);
        }

        @Override public void onFinished(File recording, String message) {
            if (isDestroyed()) return;
            CallRecordingService.consumeResult();
            finishCallUi(recording, message);
        }
    };
    private boolean transcriptFromSpeaker;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (view.screen() == RECORDING) {
                if (callMode) {
                    var state = CallRecordingService.state();
                    view.updateRecording(state.elapsedMs(), state.peakAmplitude());
                } else view.updateRecording(session.elapsedMillis(), session.peakAmplitude());
                handler.postDelayed(this, RecordingVisuals.sampleInterval());
            } else if (player != null && player.isPlaying()) {
                if (inFlightSeek < 0) updatePlayback(true, player.getCurrentPosition());
                handler.postDelayed(this, 65);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new ReflectPreferences(this);
        view = new ReflectView(this, this, preferences);
        transcriptStore = new ReflectTranscriptStore(getFilesDir());
        conversationStore = new ReflectConversationStore(this);
        titles = new ReflectConversationTitles(this, conversationStore, transcriptStore, imports, recording -> {
            transcripts.titleChanged(recording);
            refreshRecordings();
        });
        profiles = new SpeakerProfiles(this);
        SpeakerDirectory speakerDirectory = new SpeakerDirectory(getFilesDir(), transcriptStore, profiles);
        profilesUi = new SpeakerProfilesUi(this, view, profiles, speakerDirectory, imports, this::notice,
                () -> {
                    if (transcripts != null) transcripts.refreshSpeakerNames();
                    refreshRecordings();
                });
        transcripts = new ReflectTranscriptController(this, view, preferences, transcriptStore, conversationStore, titles, imports,
                this::notice, this::playFrom, this::play, this::refreshRecordings, profiles, profilesUi);
        setContentView(view);
        fullscreen();
        imports.execute(() -> {
            try { CallRecordingService.recover(getApplicationContext()); }
            catch (IOException | RuntimeException unavailable) {
                runOnUiThread(() -> { if (!isDestroyed()) notice("Не удалось восстановить прерванную запись звонка"); });
            }
            if (!isDestroyed()) refreshRecordings();
        });
        if (state != null && !CallRecordingService.active()) {
            callMode = state.getBoolean("callMode", false);
            view.callMode(callMode);
            ReflectView.Screen tab = ReflectView.Screen.valueOf(state.getString("tab", HOME.name()));
            if (ReflectView.isTab(tab)) view.show(tab);
            String file = state.getString("transcriptAudio", state.getString("audio"));
            String speakerProfile = state.getString("speakerProfile");
            if (speakerProfile != null) {
                profilesUi.openProfile(speakerProfile);
            } else if (file != null && new File(file).isFile()) {
                try {
                    if (state.getBoolean("transcript")) {
                        openLocalDetail(new File(file), state.getString("transcriptTitle", "Запись"),
                                state.getBoolean("recordingDetails"), state.getBoolean("transcriptProcessing"));
                        String returnProfile = state.getString("transcriptReturnProfile");
                        if (returnProfile != null) {
                            profilesUi.selectProfile(returnProfile);
                            transcriptFromSpeaker = true;
                        }
                    } else review(new File(file));
                }
                catch (IOException | RuntimeException error) { notice("Не удалось открыть запись"); }
            } else if (state.getBoolean("ready")) view.show(READY);
            transcripts.restoreExport(state);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (audio != null) state.putString("audio", audio.getAbsolutePath());
        state.putBoolean("ready", view.screen() == READY);
        state.putBoolean("callMode", callMode);
        state.putString("tab", view.tab().name());
        if (view.screen() == SPEAKER_PROFILE) state.putString("speakerProfile", profilesUi.profileId());
        if (transcriptFromSpeaker && view.screen() == TRANSCRIPT) {
            state.putString("transcriptReturnProfile", profilesUi.profileId());
        }
        transcripts.saveState(state);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) fullscreen();
    }

    private void fullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void toggleMenu() { view.show(view.screen() == MENU ? view.tab() : MENU); }

    @Override public void openRecorder() {
        if (CallRecordingService.active()) {
            callObserver.onState(CallRecordingService.state());
            return;
        }
        callMode = false;
        view.callMode(false);
        view.show(READY);
    }

    @Override public void openCallRecorder() {
        if (Build.VERSION.SDK_INT < 29) {
            notice("Захват звука устройства доступен начиная с Android 10.");
            return;
        }
        if (CallRecordingService.active()) {
            callObserver.onState(CallRecordingService.state());
            return;
        }
        callMode = true;
        view.callMode(true);
        view.show(READY);
        ReflectActionSheet information = view.actionSheet(this, "Запись звонка");
        var description = information.style.text(
                "Запишем микрофон и доступный звук приложений. Android может запретить запись голоса собеседника "
                + "в телефонном звонке или приложении конференции. Запись продолжится при сворачивании.",
                15, ReflectPageStyle.MUTED, information.style.art.regular);
        information.style.append(information.root, description, 0, 12);
        information.option("Понятно", () -> {});
        information.show();
    }

    @Override public void quickRecord() {
        openRecorder();
        startRecording();
    }

    @Override public void startRecording() {
        if (callMode) {
            startCallCapture();
            return;
        }
        if (view.screen() == PAUSED) {
            try {
                session.resume();
                view.show(RECORDING);
                handler.post(heartbeat);
            } catch (RuntimeException error) {
                notice("Не удалось продолжить запись");
            }
            return;
        }
        if (view.screen() != READY) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
            return;
        }
        try {
            session.start(this);
            view.updateRecording(0, 0);
            view.show(RECORDING);
            handler.post(heartbeat);
        } catch (IOException | RuntimeException error) {
            notice("Не удалось включить микрофон");
        }
    }

    private void startCallCapture() {
        if (CallRecordingService.active()) {
            if (CallRecordingService.state().paused()) CallRecordingService.resume();
            return;
        }
        if (projectionPending || view.screen() != READY || Build.VERSION.SDK_INT < 29) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
            return;
        }
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent consent = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            projectionPending = true;
            startActivityForResult(consent, CALL_CAPTURE);
        } catch (RuntimeException unavailable) {
            projectionPending = false;
            notice("Не удалось запросить захват звука устройства");
        }
    }

    private void finishCallUi(File recording, String message) {
        handler.removeCallbacks(heartbeat);
        callMode = false;
        view.callMode(false);
        refreshRecordings();
        if (recording != null && recording.isFile()) {
            try { review(recording); }
            catch (IOException | RuntimeException invalid) {
                view.show(HOME);
                notice("Запись сохранена в библиотеке, но её не удалось открыть");
            }
        } else if (view.screen() == RECORDING || view.screen() == PAUSED) view.show(READY);
        if (message != null) notice(message);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRecording();
            else notice("Для записи нужен доступ к микрофону");
        }
    }

    @Override public void pauseRecording() {
        if (view.screen() != RECORDING) return;
        if (callMode) {
            CallRecordingService.pause();
            return;
        }
        try {
            session.pause();
            handler.removeCallbacks(heartbeat);
            view.updateRecording(session.elapsedMillis(), 0);
            view.show(PAUSED);
        } catch (RuntimeException error) {
            notice("Не удалось поставить запись на паузу");
        }
    }

    private boolean hasRecordingDraft() {
        return view.screen() == RECORDING || view.screen() == PAUSED;
    }

    @Override public void finishRecording() {
        if (!hasRecordingDraft()) return;
        if (callMode) {
            CallRecordingService.finish();
            return;
        }
        if (recordingExit != null) recordingExit.dismiss();
        handler.removeCallbacks(heartbeat);
        File recorded = null;
        try {
            recorded = session.finish();
            review(recorded);
        } catch (IOException | RuntimeException error) {
            if (recorded != null) recorded.delete();
            view.show(READY);
            notice("Запись слишком короткая или недоступна");
        }
    }

    @Override public void closeRecorder() {
        if (callMode && CallRecordingService.active()) {
            ReflectActionSheet confirmation = view.actionSheet(this, "Завершить запись звонка?");
            confirmation.option("Завершить и сохранить", CallRecordingService::finish);
            confirmation.option("Продолжить запись", () -> {});
            confirmation.option("Удалить", ReflectPageStyle.DANGER, CallRecordingService::cancel);
            confirmation.show();
            return;
        }
        if (hasRecordingDraft()) {
            if (recordingExit != null) return;
            pauseRecording();
            recordingExit = new AlertDialog.Builder(this)
                    .setTitle("Завершить запись?")
                    .setPositiveButton("Прослушать", (dialog, which) -> finishRecording())
                    .setNegativeButton("Остаться", null)
                    .setNeutralButton("Удалить", (dialog, which) -> discardRecording())
                    .create();
            recordingExit.setOnDismissListener(dialog -> recordingExit = null);
            recordingExit.show();
            return;
        }
        discardRecording();
    }

    private void discardRecording() {
        cancelReviewWaveform();
        handler.removeCallbacks(heartbeat);
        session.cancel();
        callMode = false;
        view.callMode(false);
        stopPlayer();
        discardDraft();
        boolean processing = view.screen() == PROCESSING;
        boolean transcript = view.screen() == TRANSCRIPT;
        String returnProfile = transcript && transcriptFromSpeaker ? profilesUi.profileId() : null;
        transcriptFromSpeaker = false;
        if (returnProfile != null) profilesUi.openProfile(returnProfile);
        else view.show(processing ? HOME : view.tab());
        if (processing || transcript) {
            transcripts.close();
            refreshRecordings();
        }
    }

    @Override public void attach() {
        if (importing) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*");
        startActivityForResult(picker, PICK_AUDIO);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CALL_CAPTURE) {
            projectionPending = false;
            if (result == RESULT_OK && data != null) {
                try { CallRecordingService.start(this, result, data); }
                catch (RuntimeException unavailable) { notice("Не удалось начать запись звонка"); }
            } else notice("Захват звука устройства не разрешён");
            return;
        }
        if (transcripts.onActivityResult(request, result, data)) return;
        if (request != PICK_AUDIO || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String mime = getContentResolver().getType(uri);
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        importing = true;
        imports.execute(() -> importAudio(uri, extension));
    }

    private void importAudio(Uri uri, String extension) {
        File imported = null;
        try (InputStream source = getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("Could not open audio");
            imported = File.createTempFile("reflect-", extension == null ? ".audio" : "." + extension, getCacheDir());
            try (var output = Files.newOutputStream(imported.toPath())) {
                AudioImport.copy(source, output, AudioImport.MAX_BYTES);
            }
            int length = RecordingSession.duration(imported);
            File ready = imported;
            runOnUiThread(() -> {
                importing = false;
                if (isDestroyed() || (!ReflectView.isTab(view.screen()) && view.screen() != MENU)) ready.delete();
                else showReview(ready, length);
            });
        } catch (IOException | RuntimeException error) {
            if (imported != null) imported.delete();
            runOnUiThread(() -> {
                importing = false;
                if (!isDestroyed()) notice(error instanceof AudioImport.TooLarge
                        ? "Аудиофайл превышает 1 ГиБ" : "Не удалось открыть аудиофайл");
            });
        }
    }

    private void review(File file) throws IOException { showReview(file, RecordingSession.duration(file)); }

    private void showReview(File file, int length) {
        cancelReviewWaveform();
        view.setReviewWave(null);
        audio = file;
        duration = length;
        view.updateReviewPlayback(time(duration), false, 0, duration);
        view.show(REVIEW);
        reviewWaveform = ReflectEngine.previewWaveform(this, Uri.fromFile(file), length, bars -> {
            if (file.equals(audio) && view.screen() == REVIEW && !isDestroyed()) view.setReviewWave(bars);
        });
    }

    private void cancelReviewWaveform() {
        if (reviewWaveform != null) {
            reviewWaveform.cancel();
            reviewWaveform = null;
        }
    }

    @Override public void play() {
        if (audio == null) return;
        try {
            preparePlayer();
            handler.removeCallbacks(heartbeat);
            if (player.isPlaying()) {
                player.pause();
                updatePlayback(false, seekTarget >= 0 ? seekTarget : player.getCurrentPosition());
            } else {
                player.start();
                handler.post(heartbeat);
            }
        } catch (IOException | RuntimeException error) {
            stopPlayer();
            notice("Не удалось воспроизвести запись");
        }
    }

    private void preparePlayer() throws IOException {
        if (player != null) return;
        player = new MediaPlayer();
        player.setDataSource(audio.getAbsolutePath());
        player.setOnCompletionListener(completed -> {
            if (completed == player && inFlightSeek < 0) stopPlayer();
        });
        player.setOnSeekCompleteListener(this::seekCompleted);
        player.setOnErrorListener((failed, what, extra) -> {
            if (failed == player) {
                stopPlayer();
                notice("Не удалось воспроизвести запись");
            }
            return true;
        });
        player.prepare();
    }

    private void playFrom(int millis) {
        if (audio == null) return;
        try {
            preparePlayer();
            requestSeek(millis);
            player.start();
            publishSeek(true);
            handler.removeCallbacks(heartbeat);
            handler.post(heartbeat);
        } catch (IOException | RuntimeException error) {
            stopPlayer();
            notice("Не удалось воспроизвести запись");
        }
    }

    @Override public void seekPlayback(float fraction) {
        if (audio == null || duration <= 0 || !Float.isFinite(fraction)) return;
        seekPlaybackMillis(Math.round(fraction * duration));
    }

    @Override public void seekPlaybackMillis(int millis) {
        if (audio == null || duration <= 0) return;
        try {
            preparePlayer();
            requestSeek(millis);
            publishSeek(player.isPlaying());
        } catch (IOException | RuntimeException error) {
            stopPlayer();
            notice("Не удалось перемотать запись");
        }
    }

    private void requestSeek(int millis) {
        seekTarget = Math.max(0, Math.min(millis, duration - 1));
        if (inFlightSeek < 0) startPendingSeek();
    }

    private void startPendingSeek() {
        inFlightSeek = seekTarget;
        player.seekTo(inFlightSeek, MediaPlayer.SEEK_CLOSEST);
    }

    private void publishSeek(boolean playing) {
        view.updateReviewPlayback(time(duration), playing, seekTarget, duration);
        view.transcriptView().playbackSeeked(playing, seekTarget);
    }

    private void seekCompleted(MediaPlayer source) {
        if (source != player || inFlightSeek < 0) return;
        try {
            if (seekTarget != inFlightSeek) {
                startPendingSeek();
                return;
            }
            inFlightSeek = seekTarget = -1;
            updatePlayback(source.isPlaying(), source.getCurrentPosition());
        } catch (RuntimeException error) {
            stopPlayer();
            notice("Не удалось перемотать запись");
        }
    }

    @Override public void save() {
        if (view.screen() != REVIEW || !transcripts.canStartBackground()) return;
        File saved = saveAudio();
        if (saved == null) return;
        closeRecorder();
        transcripts.startBackground(saved);
    }

    @Override public void transcribeReview() {
        if (view.screen() != REVIEW || !transcripts.canStartBackground()) return;
        cancelReviewWaveform();
        File saved = saveAudio();
        if (saved == null) return;
        stopPlayer();
        transcripts.open(saved, "Разговор", time(duration), duration, false, true, true);
        loadConversationWaveform(saved, duration);
    }

    private File saveAudio() {
        if (audio == null) return null;
        File directory = new File(getFilesDir(), "recordings");
        if (directory.equals(audio.getParentFile())) return audio;
        String name = audio.getName();
        String suffix = name.substring(name.lastIndexOf('.'));
        File saved = new File(directory, UUID.randomUUID() + suffix);
        try {
            Files.createDirectories(directory.toPath());
            Files.copy(audio.toPath(), saved.toPath());
            cancelReviewWaveform();
            stopPlayer();
            discardDraft();
            audio = saved;
            refreshRecordings();
            return saved;
        } catch (IOException error) {
            saved.delete();
            notice("Не удалось сохранить запись");
            return null;
        }
    }

    @Override public void openRecording(ReflectCatalog.Recording recording) {
        openLocalDetail(recording, true);
    }

    private void openLocalDetail(ReflectCatalog.Recording recording, boolean recordingDetails) {
        try { openLocalDetail(recording.file, recording.title, recordingDetails, false); }
        catch (IOException | RuntimeException error) { notice("Не удалось открыть запись"); }
    }

    private void openLocalDetail(File file, String title, boolean recordingDetails, boolean showProcessing)
            throws IOException {
        int length = RecordingSession.duration(file);
        cancelReviewWaveform();
        stopPlayer();
        discardDraft();
        audio = file;
        duration = length;
        transcripts.open(file, title, time(length), length, recordingDetails, showProcessing, false);
        loadConversationWaveform(file, length);
    }

    private void loadConversationWaveform(File file, int length) {
        reviewWaveform = ReflectEngine.previewWaveform(this, Uri.fromFile(file), length, bars -> {
            if (file.equals(audio) && !isDestroyed()) view.transcriptView().waveform(bars);
        });
    }

    private void refreshRecordings() {
        File directory = new File(getFilesDir(), "recordings");
        imports.execute(() -> {
            var recordings = ReflectCatalog.localRecordings(directory, transcriptStore, profiles);
            var conversations = ReflectCatalog.localConversations(recordings, conversationStore);
            runOnUiThread(() -> {
                if (!isDestroyed()) view.setRecordings(recordings);
            });
            publishConversations(conversations);
        });
    }

    private void publishConversations(List<ReflectCatalog.Conversation> conversations) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            view.setConversations(conversations);
            titles.catalog(conversations);
        });
    }

    @Override public void openConversation(ReflectCatalog.Conversation conversation) {
        try { openLocalDetail(conversation.recording.file, conversation.title, false, false); }
        catch (IOException | RuntimeException error) { notice("Не удалось открыть разговор"); }
    }

    @Override public Future<?> searchConversationQuotes(List<ReflectCatalog.Conversation> conversations, String query,
                                                       Consumer<List<ReflectCatalog.Quote>> result) {
        if (isDestroyed() || imports.isShutdown()) return CompletableFuture.completedFuture(null);
        return imports.submit(() -> {
            var matches = ReflectCatalog.searchConversationQuotes(conversations, query, transcriptStore);
            runOnUiThread(() -> { if (!isDestroyed()) result.accept(matches); });
        });
    }

    @Override public void openConversationQuote(ReflectCatalog.Quote quote) {
        try {
            openLocalDetail(quote.conversation().recording.file, quote.conversation().title, false, false);
            seekPlaybackMillis(quote.startMs());
        } catch (IOException | RuntimeException failure) {
            notice("Не удалось открыть реплику");
        }
    }

    @Override public void openRecordingTranscript() {
        transcripts.openRecordingTranscript();
    }

    @Override public void closeTranscript() { closeRecorder(); }
    @Override public void openExportOptions() { transcripts.exportOptions(); }
    @Override public void closeExport() { transcripts.closeExport(); }
    @Override public void selectExport(RecordingExport format) { transcripts.selectExport(format); }
    @Override public void shareExport() { transcripts.shareExport(); }
    @Override public void saveExport() { transcripts.saveExport(); }
    @Override public void importTranscript() { transcripts.pick(); }
    @Override public void transcribeRecording() { transcripts.transcribe(); }
    @Override public void cancelTranscription() { transcripts.cancelTranscription(); }
    @Override public void toggleSummary() { transcripts.toggleSummary(); }
    @Override public void retrySummary() { transcripts.retrySummary(); }
    @Override public void requestDeleteConversation() { transcripts.requestDeleteConversation(this::closeTranscript); }
    @Override public void openTranscriptOptions() { transcripts.options(); }
    @Override public void openTranscriptStyle() { transcripts.styleOptions(); }
    @Override public void playTranscriptAudio() { play(); }
    @Override public void openTranscriptLine(ReflectTranscript.Line line) { transcripts.lineOptions(line); }
    @Override public void recheckTranscriptLine(ReflectTranscript.Line line) { transcripts.recheck(line); }
    @Override public void openOverlapInfo(ReflectTranscript.Line line) { transcripts.overlapInfo(line); }

    @Override public void refreshSpeakers() { profilesUi.refreshSpeakers(); }

    @Override public void addSpeaker() { profilesUi.addSpeaker(); }
    @Override public void openSpeaker(ReflectCatalog.Speaker speaker) { profilesUi.openProfile(speaker.id); }
    @Override public void closeSpeakerProfile() { profilesUi.closeProfile(); }
    @Override public void openSpeakerMenu() { profilesUi.openMenu(); }
    @Override public void renameSpeakerProfile() { profilesUi.renameProfile(); }

    @Override public void openSpeakerUtterance(SpeakerDirectory.Utterance utterance) {
        if (view.screen() != SPEAKER_PROFILE || profilesUi.profileId() == null) return;
        String profile = profilesUi.profileId();
        imports.execute(() -> {
            boolean deleted;
            try { deleted = conversationStore.isDeleted(utterance.recording()); }
            catch (RuntimeException unavailable) { deleted = false; }
            boolean recordingDetails = deleted;
            runOnUiThread(() -> {
                if (isDestroyed() || view.screen() != SPEAKER_PROFILE || !profile.equals(profilesUi.profileId())) return;
                try {
                    openLocalDetail(utterance.recording(), "Разговор", recordingDetails, false);
                    transcriptFromSpeaker = true;
                    playFrom(utterance.startMs());
                } catch (IOException | RuntimeException error) {
                    notice("Не удалось открыть запись");
                }
            });
        });
    }

    @Override public void openMicrophoneSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    private void discardDraft() {
        if (audio != null && getCacheDir().equals(audio.getParentFile())) audio.delete();
        audio = null;
    }

    private void stopPlayer() {
        handler.removeCallbacks(heartbeat);
        inFlightSeek = seekTarget = -1;
        if (player != null) {
            player.release();
            player = null;
        }
        updatePlayback(false, 0);
    }

    private void updatePlayback(boolean playing, int position) {
        view.updateReviewPlayback(time(duration), playing, position, duration);
        view.transcriptView().playback(playing, position);
    }

    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void notice(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (view.screen() == EXPORT) transcripts.closeExport();
        else if (view.screen() == MENU) toggleMenu();
        else if (view.screen() == SPEAKER_PROFILE) closeSpeakerProfile();
        else if (ReflectView.isTab(view.screen()) && view.screen() != HOME) view.show(HOME);
        else if (view.screen() != HOME) closeRecorder();
        else super.onBackPressed();
    }

    @Override protected void onStop() {
        titles.foreground(false);
        CallRecordingService.removeObserver(callObserver);
        if (hasRecordingDraft() && !callMode) finishRecording();
        stopPlayer();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        CallRecordingService.observe(callObserver);
        var callResult = CallRecordingService.consumeResult();
        if (callResult != null) finishCallUi(callResult.recording(), callResult.message());
        transcripts.resumeProcessing();
        titles.foreground(true);
        refreshRecordings();
        refreshSpeakers();
        transcripts.refreshSpeakerNames();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (view != null) view.trimMemory(level);
    }

    @Override protected void onDestroy() {
        cancelReviewWaveform();
        CallRecordingService.removeObserver(callObserver);
        session.cancel();
        if (isFinishing()) discardDraft();
        titles.destroy();
        transcripts.destroy();
        profilesUi.close();
        imports.execute(() -> {
            profiles.close();
            conversationStore.close();
        });
        imports.shutdown();
        super.onDestroy();
    }
}
