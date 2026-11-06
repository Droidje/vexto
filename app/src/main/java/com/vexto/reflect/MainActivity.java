package com.vexto.reflect;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
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
import androidx.core.content.FileProvider;
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
    private File audio;
    private int duration;
    private MediaPlayer player;
    private boolean importing;
    private boolean designPreview;
    private boolean previewSpeakersEmpty;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (view.screen() == RECORDING) {
                view.updateRecording(time(session.elapsedMillis()), session.amplitude());
                handler.postDelayed(this, 65);
            } else if (player != null && player.isPlaying()) {
                view.updatePlayback(time(duration), true, player.getCurrentPosition() / (float) duration);
                handler.postDelayed(this, 65);
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
        transcripts = new ReflectTranscriptController(this, view, preferences, transcriptStore, imports,
                this::notice, this::playFrom, this::refreshRecordings);
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
                        openLocalTranscript(new File(file), state.getString("transcriptTitle", "Запись"));
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
        if (view.screen() != READY) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
            return;
        }
        try {
            session.start(this);
            view.updateRecording("0:00", 0);
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

    @Override public void finishRecording() {
        if (view.screen() != RECORDING) return;
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
        handler.removeCallbacks(heartbeat);
        session.cancel();
        stopPlayer();
        discardDraft();
        if (view.screen() == TRANSCRIPT) transcripts.close();
        view.show(view.tab());
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

    @Override public void send() {
        File saved = saveAudio();
        if (saved == null) return;
        stopPlayer();
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".recordings", saved);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("audio/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(getString(R.string.app_name), uri));
        startActivity(Intent.createChooser(send, "Поделиться записью"));
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

    @Override public void openRecording(ReflectCatalog.Recording recording) {
        if (recording.file == null) {
            if (designPreview) transcripts.openPreview(recording.title);
            return;
        }
        try { openLocalTranscript(recording.file, recording.title); }
        catch (IOException | RuntimeException error) { notice("Не удалось открыть запись"); }
    }

    private void openLocalTranscript(File file, String title) throws IOException {
        int length = RecordingSession.duration(file);
        stopPlayer();
        discardDraft();
        audio = file;
        duration = length;
        transcripts.open(file, title, time(length));
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
            openRecording(conversation.recording);
        } else if (designPreview) transcripts.openPreview(conversation.title);
    }

    @Override public void closeTranscript() { closeRecorder(); }
    @Override public void exportTranscript() { transcripts.export(); }
    @Override public void importTranscript() { transcripts.pick(); }
    @Override public void openTranscriptOptions() { transcripts.options(); }
    @Override public void openTranscriptStyle() { transcripts.styleOptions(); }
    @Override public void playTranscriptAudio() { play(); }
    @Override public void openTranscriptLine(ReflectTranscript.Line line) { transcripts.lineOptions(line); }

    @Override public void toggleSpeakerPreview() {
        if (!designPreview) return;
        previewSpeakersEmpty = !previewSpeakersEmpty;
        refreshSpeakers();
    }

    private void refreshSpeakers() {
        view.setSpeakers(designPreview && !previewSpeakersEmpty
                ? ReflectDesignPreview.speakers() : Collections.emptyList());
    }

    @Override public void inviteSpeakers() {
        String configuredUrl = BuildConfig.REFLECT_INVITE_URL.trim();
        Uri uri = Uri.parse(configuredUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getUserInfo() != null) {
            notice("Ссылка для приглашения ещё не настроена");
            return;
        }
        Intent invitation = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, configuredUrl);
        startActivity(Intent.createChooser(invitation, "Отправить ссылку"));
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
        if (view.screen() == RECORDING) finishRecording();
        stopPlayer();
        super.onStop();
    }

    @Override protected void onDestroy() {
        session.cancel();
        if (isFinishing()) discardDraft();
        imports.shutdown();
        super.onDestroy();
    }
}
