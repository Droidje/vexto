package com.vexto.reflect;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Connects the conversation page to private storage and the system document picker. */
final class ReflectTranscriptController {
    private static final int PICK_JSON = 3;
    private final Activity activity;
    private final ReflectView view;
    private final ReflectPreferences preferences;
    private final ReflectTranscriptStore store;
    private final ExecutorService io;
    private final Consumer<String> notice;
    private final IntConsumer playFrom;
    private final Runnable playAudio;
    private final Runnable refreshCatalog;
    private final SpeakerProfiles profiles;
    private final SpeakerProfilesUi profilesUi;
    private final ReflectLocalTranscription local;
    private final ReflectExportController exports;
    private RecordingExport restoreExport;
    private File file;
    private final Set<File> importing = new HashSet<>();
    private ReflectTranscript transcript;
    private RecordingMetadata metadata;
    private String title, length, issue;
    private boolean loading, recordingDetails;
    private int generation, transcriptRequest, durationMs;

    ReflectTranscriptController(Activity activity, ReflectView view, ReflectPreferences preferences,
                                ReflectTranscriptStore store, ExecutorService io, Consumer<String> notice,
                                IntConsumer playFrom, Runnable playAudio, Runnable refreshCatalog,
                                SpeakerProfiles profiles, SpeakerProfilesUi profilesUi) {
        this.activity = activity;
        this.view = view;
        this.preferences = preferences;
        this.store = store;
        this.io = io;
        this.notice = notice;
        this.playFrom = playFrom;
        this.playAudio = playAudio;
        this.refreshCatalog = refreshCatalog;
        this.profiles = profiles;
        this.profilesUi = profilesUi;
        exports = new ReflectExportController(activity, view, io, profiles, notice);
        local = new ReflectLocalTranscription(activity, store, profiles, io, this::processingChanged,
                this::transcriptionFinished);
    }

    void open(File recording, String fallback, String length, int durationMs, boolean recordingDetails,
              boolean startImmediately) {
        int current = ++generation;
        file = recording;
        title = fallback;
        this.length = length;
        this.durationMs = durationMs;
        this.recordingDetails = recordingDetails;
        transcript = null;
        metadata = null;
        loading = true;
        issue = null;
        view.transcriptView().speakerNames(Collections.emptyMap());
        render();
        if (startImmediately) view.processingView().content(length, null);
        view.show(startImmediately ? ReflectView.Screen.PROCESSING : ReflectView.Screen.TRANSCRIPT);
        io.execute(() -> {
            String resolvedTitle = store.title(recording, fallback);
            RecordingMetadata properties = RecordingMetadata.read(recording);
            try {
                ReflectTranscript loaded = store.load(recording);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    if (current != generation) {
                        // The user can send processing to the background before metadata is loaded.
                        if (startImmediately && loaded == null && !importing.contains(recording)) {
                            if (local.busy()) notice.accept("Дождитесь завершения текущей обработки.");
                            else local.start(recording);
                        }
                        return;
                    }
                    title = resolvedTitle;
                    metadata = properties;
                    transcript = loaded;
                    loading = false;
                    render();
                    refreshSpeakerNames(current, recording);
                    if (startImmediately && loaded == null) transcribe();
                    else showTranscriptAfterProcessing();
                });
            } catch (IOException | RuntimeException invalid) {
                String readIssue = "Не удалось прочитать сохранённый транскрипт. Можно импортировать другой JSON.";
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    if (current != generation) {
                        if (startImmediately) notice.accept(readIssue);
                        return;
                    }
                    title = resolvedTitle;
                    metadata = properties;
                    issue = readIssue;
                    loading = false;
                    render();
                    showTranscriptAfterProcessing();
                    refreshSpeakerNames(current, recording);
                });
            }
        });
    }

    void close() {
        ++generation;
        file = null;
        transcript = null;
        metadata = null;
        view.transcriptView().clear();
    }

    void destroy() { local.close(); }

    void transcribe() {
        if (file == null || loading || recordingDetails || transcript != null) return;
        if (local.busy() || importing.contains(file)) {
            if (local.progress(file) == null) {
                showTranscriptAfterProcessing();
                notice.accept("Дождитесь завершения текущей обработки.");
            }
            return;
        }
        issue = null;
        render();
        local.start(file);
    }

    void cancelTranscription() { local.cancel(file); }

    void saveState(Bundle state) {
        if (view.screen() != ReflectView.Screen.TRANSCRIPT && view.screen() != ReflectView.Screen.PROCESSING
                && view.screen() != ReflectView.Screen.EXPORT) return;
        if (view.screen() == ReflectView.Screen.EXPORT) exports.saveState(state);
        state.putBoolean("transcript", true);
        state.putBoolean("transcriptProcessing", view.screen() == ReflectView.Screen.PROCESSING);
        state.putBoolean("recordingDetails", recordingDetails);
        state.putString("transcriptTitle", title);
        if (file != null) state.putString("transcriptAudio", file.getAbsolutePath());
    }

    void pick() {
        if (file == null || loading) return;
        if (local.progress(file) != null || importing.contains(file)) {
            notice.accept("Дождитесь завершения текущей обработки.");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        activity.startActivityForResult(picker, PICK_JSON);
    }

    void exportOptions() {
        if (file == null || loading) {
            notice.accept("Данные записи ещё загружаются");
            return;
        }
        exports.open(file, transcript, metadata, title, length, RecordingExport.JSON);
    }

    void closeExport() { exports.close(); }
    void selectExport(RecordingExport format) { exports.select(format); }
    void shareExport() { exports.share(); }
    void saveExport() { exports.save(); }

    void restoreExport(Bundle state) {
        if (!state.containsKey("exportFormat")) return;
        restoreExport = ReflectExportController.restoredFormat(state);
        restoreExportIfReady();
    }

    private void restoreExportIfReady() {
        if (restoreExport == null || loading || file == null) return;
        RecordingExport format = restoreExport;
        restoreExport = null;
        exports.open(file, transcript, metadata, title, length, format);
    }

    boolean onActivityResult(int request, int result, Intent data) {
        RecordingExport format = RecordingExport.forRequest(request);
        if (request != PICK_JSON && format == null) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || file == null) return true;
        File recording = file;
        Uri uri = data.getData();
        int current = generation;
        int duration = durationMs;
        if (request == PICK_JSON) {
            if (local.progress(recording) != null || !importing.add(recording)) return true;
            transcriptRequest++;
        }
        ReflectTranscript snapshot = transcript;
        String exportTitle = title;
        io.execute(() -> {
            try {
                if (request == PICK_JSON) {
                    ReflectTranscript imported;
                    try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IOException("No transcript input");
                        imported = ReflectTranscript.parse(input);
                    }
                    store.save(recording, imported);
                    String message = "Транскрипт импортирован";
                    try { profiles.assign(recording, imported); }
                    catch (IOException | RuntimeException indexFailure) {
                        message = "Транскрипт импортирован. Не удалось обновить список спикеров.";
                    }
                    String resultMessage = message;
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed()) refreshCatalog.run();
                    });
                    deliver(current, () -> {
                        transcript = imported;
                        loading = false;
                        issue = null;
                        view.transcriptView().speakerNames(Collections.emptyMap());
                        render();
                        refreshSpeakerNames(current, recording);
                        notice.accept(resultMessage);
                    });
                } else {
                    ReflectTranscript source = snapshot;
                    if (format != RecordingExport.AUDIO && source == null) {
                        source = store.load(recording);
                    }
                    try (OutputStream stream = activity.getContentResolver().openOutputStream(uri, "wt")) {
                        if (stream == null) throw new IOException("No export output");
                        format.write(stream, recording, source, exportTitle,
                                format == RecordingExport.MARKDOWN && source != null ? profiles.exportNames(recording, source) : Collections.emptyMap());
                    }
                    deliver(current, () -> notice.accept("Файл экспортирован"));
                }
            } catch (IOException | RuntimeException error) {
                deliver(current, () -> notice.accept(request == PICK_JSON
                        ? "Не удалось импортировать JSON с репликами" : "Не удалось экспортировать файл"));
            } finally {
                if (request == PICK_JSON) activity.runOnUiThread(() -> importing.remove(recording));
            }
        });
        return true;
    }

    void options() {
        Sheet menu = sheet(recordingDetails ? "Действия записи" : "Действия разговора");
        if (file != null) {
            if (!recordingDetails && transcript != null && !transcript.lines.isEmpty()) {
                menu.option(view.transcriptView().isPlaying() ? "Приостановить" : "Прослушать аудио", playAudio);
            }
            menu.option("Переименовать", () -> rename());
            if (!recordingDetails) menu.option("Импортировать транскрипт", this::pick);
        }
        if (!recordingDetails) menu.option("Настройки чтения", this::styleOptions);
        menu.show();
    }

    void styleOptions() {
        Sheet menu = sheet("Размер текста");
        int[] sizes = {14, 16, 20};
        String[] labels = {"Мелкий", "Обычный", "Крупный"};
        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            String label = (preferences.textSize() == size ? "✓  " : "") + labels[i];
            menu.option(label, () -> {
                preferences.setTextSize(size);
                view.transcriptView().refreshStyle();
                view.refreshSettings();
            });
        }
        menu.show();
    }

    void lineOptions(ReflectTranscript.Line line) {
        Sheet menu = sheet("Реплика");
        TextView excerpt = menu.style.text(line.text, 15, ReflectPageStyle.MUTED, menu.style.art.regular);
        excerpt.setMaxLines(5);
        menu.style.append(menu.root, excerpt, 0, 12);
        if (file != null) menu.option("Прослушать с метки", () -> playFrom.accept(line.startMs));
        if (file != null && transcript != null) {
            File recording = file;
            ReflectTranscript visible = transcript;
            int current = generation, request = transcriptRequest;
            menu.option("Привязать спикера", () -> profilesUi.linkSpeaker(recording, visible, line.speakerId,
                    () -> current == generation && request == transcriptRequest,
                    () -> { if (current == generation) refreshSpeakerNames(current, recording); }));
        }
        menu.option("Скопировать текст", () -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.app_name), line.text));
            }
        });
        menu.option("Поделиться репликой", () -> activity.startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, line.text),
                "Поделиться репликой")));
        menu.show();
    }

    private void rename() {
        File recording = file;
        if (recording == null) return;
        Sheet editor = sheet(recordingDetails ? "Название записи" : "Название разговора");
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        input.setTypeface(editor.style.art.regular);
        input.setTextColor(Color.WHITE);
        input.setText(title);
        input.setSelectAllOnFocus(true);
        input.setBackground(editor.style.rounded(0xff333333, 14));
        input.setPadding(editor.style.px(12), editor.style.px(10), editor.style.px(12), editor.style.px(10));
        editor.style.append(editor.root, input, 0, 10);
        editor.option("Сохранить", () -> {
            String newTitle = input.getText().toString().trim();
            if (newTitle.isEmpty()) {
                notice.accept("Введите название разговора");
                return;
            }
            int current = generation;
            int duration = durationMs;
            io.execute(() -> {
                try {
                    store.rename(recording, newTitle);
                    deliver(current, () -> {
                        title = newTitle;
                        render();
                        refreshCatalog.run();
                    });
                } catch (IOException | RuntimeException failure) {
                    deliver(current, () -> notice.accept("Не удалось сохранить название"));
                }
            });
        });
        input.requestFocus();
        editor.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        editor.show();
    }

    private void render() {
        view.transcriptView().content(title, length, transcript, file != null,
                loading, issue, recordingDetails, metadata, local.progress(file));
        restoreExportIfReady();
    }

    private void processingChanged(File recording) {
        if (!recording.equals(file) || recordingDetails) return;
        var progress = local.progress(file);
        if (view.screen() == ReflectView.Screen.PROCESSING) view.processingView().content(length, progress);
        else view.transcriptView().transcription(progress);
    }

    private void transcriptionFinished(ReflectLocalTranscription.Result result) {
        if (result.recording().equals(file)) {
            if (transcript == null) {
                transcript = result.transcript();
                issue = transcript == null ? result.message() : null;
                if (transcript != null) refreshSpeakerNames(generation, file);
            }
            render();
            showTranscriptAfterProcessing();
        }
        if (result.transcript() != null || !result.recording().equals(file)) {
            notice.accept(result.message() == null ? "Расшифровка готова" : result.message());
        }
        refreshCatalog.run();
        if (result.transcript() != null && view.screen() == ReflectView.Screen.SPEAKERS) profilesUi.refreshSpeakers();
    }

    private void showTranscriptAfterProcessing() {
        if (view.screen() == ReflectView.Screen.PROCESSING) view.show(ReflectView.Screen.TRANSCRIPT);
    }

    void refreshSpeakerNames() {
        if (file != null) refreshSpeakerNames(generation, file);
    }

    private void refreshSpeakerNames(int current, File recording) {
        if (recordingDetails || transcript == null || transcript.lines.isEmpty()) return;
        int request = ++transcriptRequest;
        profilesUi.speakerNames(recording, transcript, names -> {
            if (current == generation && request == transcriptRequest) view.transcriptView().speakerNames(names);
        });
    }

    private void deliver(int current, Runnable result) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed() && current == generation) result.run();
        });
    }

    private Sheet sheet(String title) { return new Sheet(title); }

    private final class Sheet {
        final Dialog dialog = new Dialog(activity);
        final ReflectPageStyle style = view.pageStyle();
        final LinearLayout root = style.column();

        Sheet(String title) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root.setPadding(style.px(16), style.px(16), style.px(16), style.px(12));
            root.setBackground(style.rounded(ReflectPageStyle.CARD, 24));
            style.append(root, style.text(title, 18, Color.WHITE, style.art.semibold), 0, 12);
            dialog.setContentView(root);
        }

        void option(String title, Runnable action) {
            option(title, null, action);
        }

        private void option(String title, String icon, Runnable action) {
            TextView button = style.text(title, 16, Color.WHITE, style.art.regular);
            button.setMinHeight(style.px(44));
            button.setPadding(style.px(10), 0, style.px(10), 0);
            if (icon != null) {
                button.setCompoundDrawablesRelative(
                        style.art.iconDrawable(activity.getResources(), icon, style.px(22)), null, null, null);
                button.setCompoundDrawablePadding(style.px(10));
            }
            button.setBackground(style.pressable(0xff303030, 14));
            button.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
            style.append(root, button, 4, 0);
        }

        void show() {
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.CENTER);
            dialog.show();
            window.setLayout(style.px(361), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }
}
