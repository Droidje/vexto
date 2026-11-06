package com.vexto.reflect;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.vexto.reflect.ReflectView.Screen.*;

public final class MainActivity extends Activity implements ReflectView.Actions {
    private static final int MICROPHONE_PERMISSION = 1, PICK_AUDIO = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private final RecordingSession session = new RecordingSession();
    private ReflectView view;
    private ReflectPreferences preferences;
    private ReflectTranscriptStore transcriptStore;
    private ReflectTranscriptController transcripts;
    private SpeakerProfiles profiles;
    private SpeakerProfilesUi profilesUi;
    private File audio;
    private int duration;
    private MediaPlayer player;
    private AlertDialog recordingExit;
    private boolean importing;
    private boolean designPreview;
    private boolean previewSpeakersEmpty;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (view.screen() == RECORDING) {
                view.updateRecording(session.elapsedMillis(), session.peakAmplitude());
                handler.postDelayed(this, RecordingVisuals.sampleInterval());
            } else if (player != null && player.isPlaying()) {
                view.updatePlayback(time(duration), true, player.getCurrentPosition() / (float) duration);
                if (view.screen() == REVIEW) handler.postDelayed(this, 65);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        designPreview = BuildConfig.DEBUG && getIntent().getBooleanExtra("reflect_preview", false);
        previewSpeakersEmpty = state != null && state.getBoolean("previewSpeakersEmpty");
        preferences = new ReflectPreferences(this);
        view = new ReflectView(this, this, designPreview, preferences);
        transcriptStore = new ReflectTranscriptStore(getFilesDir());
        profiles = new SpeakerProfiles(this);
        profilesUi = new SpeakerProfilesUi(this, view, profiles, imports, this::notice,
                () -> { if (transcripts != null) transcripts.refreshSpeakerNames(); });
        transcripts = new ReflectTranscriptController(this, view, preferences, transcriptStore, imports,
                this::notice, this::playFrom, this::play, this::refreshRecordings, profiles, profilesUi);
        view.manageSpeakers(!designPreview);
        setContentView(view);
        fullscreen();
        refreshRecordings();
        refreshSpeakers();
        if (state != null) {
            ReflectView.Screen tab = ReflectView.Screen.valueOf(state.getString("tab", HOME.name()));
            if (ReflectView.isTab(tab)) view.show(tab);
            String file = state.getString("transcriptAudio", state.getString("audio"));
            String preview = state.getString("transcriptPreview");
            if (designPreview && preview != null) {
                transcripts.openPreview(preview);
            } else if (file != null && new File(file).isFile()) {
                try {
                    if (state.getBoolean("transcript")) {
                        openLocalDetail(new File(file), state.getString("transcriptTitle", "Запись"),
                                state.getBoolean("recordingDetails"), state.getBoolean("transcriptProcessing"));
                    } else review(new File(file));
                }
                catch (IOException | RuntimeException error) { notice("Не удалось открыть запись"); }
            } else if (state.getBoolean("ready")) view.show(READY);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (audio != null) state.putString("audio", audio.getAbsolutePath());
        state.putBoolean("ready", view.screen() == READY);
        state.putString("tab", view.tab().name());
        state.putBoolean("previewSpeakersEmpty", previewSpeakersEmpty);
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

    @Override public void openRecorder() { view.show(READY); }

    @Override public void startRecording() {
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

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRecording();
            else notice("Для записи нужен доступ к микрофону");
        }
    }

    @Override public void pauseRecording() {
        if (view.screen() != RECORDING) return;
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
        handler.removeCallbacks(heartbeat);
        session.cancel();
        stopPlayer();
        discardDraft();
        boolean processing = view.screen() == PROCESSING;
        if (processing || view.screen() == TRANSCRIPT) transcripts.close();
        view.show(processing ? HOME : view.tab());
    }

    @Override public void attach() {
        if (importing) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*");
        startActivityForResult(picker, PICK_AUDIO);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
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
            Files.copy(source, imported.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
                if (!isDestroyed()) notice("Не удалось открыть аудиофайл");
            });
        }
    }

    private void review(File file) throws IOException { showReview(file, RecordingSession.duration(file)); }

    private void showReview(File file, int length) {
        audio = file;
        duration = length;
        view.updatePlayback(time(duration), false, 0);
        view.show(REVIEW);
    }

    @Override public void play() {
        if (audio == null) return;
        try {
            preparePlayer();
            handler.removeCallbacks(heartbeat);
            if (player.isPlaying()) {
                player.pause();
                view.updatePlayback(time(duration), false, player.getCurrentPosition() / (float) duration);
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
        player.setOnCompletionListener(completed -> stopPlayer());
        player.setOnErrorListener((failed, what, extra) -> {
            stopPlayer();
            notice("Не удалось воспроизвести запись");
            return true;
        });
        player.prepare();
    }

    private void playFrom(int millis) {
        if (audio == null) return;
        try {
            preparePlayer();
            player.seekTo(Math.min(millis, Math.max(0, duration - 1)), MediaPlayer.SEEK_CLOSEST);
            player.start();
            handler.removeCallbacks(heartbeat);
            handler.post(heartbeat);
        } catch (IOException | RuntimeException error) {
            stopPlayer();
            notice("Не удалось воспроизвести запись");
        }
    }

    @Override public void save() {
        if (saveAudio() == null) return;
        closeRecorder();
        notice("Запись сохранена");
    }

    @Override public void transcribeReview() {
        if (view.screen() != REVIEW) return;
        File saved = saveAudio();
        if (saved == null) return;
        stopPlayer();
        view.show(HOME);
        transcripts.open(saved, defaultRecordingTitle(saved.getParentFile()), time(duration), duration, false, true);
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

    private static String defaultRecordingTitle(File directory) {
        String[] recordings = directory.list((parent, child) -> new File(parent, child).isFile());
        return recordings == null ? "Запись" : "Запись №" + recordings.length;
    }

    @Override public void openRecording(ReflectCatalog.Recording recording) {
        openLocalDetail(recording, true);
    }

    private void openLocalDetail(ReflectCatalog.Recording recording, boolean recordingDetails) {
        if (recording.file == null) {
            if (designPreview) transcripts.openPreview(recording.title);
            return;
        }
        try { openLocalDetail(recording.file, recording.title, recordingDetails, false); }
        catch (IOException | RuntimeException error) { notice("Не удалось открыть запись"); }
    }

    private void openLocalDetail(File file, String title, boolean recordingDetails, boolean startImmediately)
            throws IOException {
        int length = RecordingSession.duration(file);
        stopPlayer();
        discardDraft();
        audio = file;
        duration = length;
        transcripts.open(file, title, time(length), length, recordingDetails, startImmediately);
    }

    private void refreshRecordings() {
        if (designPreview) {
            view.setRecordings(ReflectDesignPreview.recordings());
            view.setConversations(ReflectDesignPreview.conversations());
            return;
        }
        File directory = new File(getFilesDir(), "recordings");
        imports.execute(() -> {
            var recordings = ReflectCatalog.localRecordings(directory, transcriptStore);
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    view.setRecordings(recordings);
                    view.setConversations(ReflectCatalog.localConversations(recordings));
                }
            });
        });
    }

    @Override public void openConversation(ReflectCatalog.Conversation conversation) {
        if (conversation.recording != null) {
            openLocalDetail(conversation.recording, false);
        } else if (designPreview) transcripts.openPreview(conversation.title);
    }

    @Override public void closeTranscript() { closeRecorder(); }
    @Override public void openExportOptions() { transcripts.exportOptions(); }
    @Override public void importTranscript() { transcripts.pick(); }
    @Override public void transcribeRecording() { transcripts.transcribe(); }
    @Override public void cancelTranscription() { transcripts.cancelTranscription(); }
    @Override public void openTranscriptOptions() { transcripts.options(); }
    @Override public void openTranscriptStyle() { transcripts.styleOptions(); }
    @Override public void playTranscriptAudio() { play(); }
    @Override public void openTranscriptLine(ReflectTranscript.Line line) { transcripts.lineOptions(line); }

    @Override public void toggleSpeakerPreview() {
        if (!designPreview) return;
        previewSpeakersEmpty = !previewSpeakersEmpty;
        refreshSpeakers();
    }

    @Override public void refreshSpeakers() {
        if (designPreview) {
            view.setSpeakers(previewSpeakersEmpty ? Collections.emptyList() : ReflectDesignPreview.speakers());
        } else profilesUi.refreshSpeakers();
    }

    @Override public void addSpeaker() { profilesUi.addSpeaker(); }
    @Override public void openSpeaker(ReflectCatalog.Speaker speaker) { profilesUi.manageSpeaker(speaker); }

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
        if (player != null) {
            player.release();
            player = null;
        }
        view.updatePlayback(time(duration), false, 0);
    }

    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void notice(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (view.screen() == MENU) toggleMenu();
        else if (ReflectView.isTab(view.screen()) && view.screen() != HOME) view.show(HOME);
        else if (view.screen() != HOME) closeRecorder();
        else super.onBackPressed();
    }

    @Override protected void onStop() {
        if (hasRecordingDraft()) finishRecording();
        stopPlayer();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!designPreview) { refreshSpeakers(); transcripts.refreshSpeakerNames(); }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (view != null) view.trimMemory(level);
    }

    @Override protected void onDestroy() {
        session.cancel();
        if (isFinishing()) discardDraft();
        transcripts.destroy();
        profilesUi.close();
        imports.execute(profiles::close);
        imports.shutdown();
        super.onDestroy();
    }
}
