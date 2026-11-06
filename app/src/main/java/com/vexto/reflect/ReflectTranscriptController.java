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
    private final Runnable refreshCatalog;
    private final ReflectBackendSync backend;
    private final ReflectBackendUi backendUi;
    private File file;
    private ReflectTranscript transcript;
    private RecordingMetadata metadata;
    private String title, length, issue;
    private String previewKey;
    private String remoteRevision;
    private boolean loading, recordingDetails;
    private int generation, transcriptRequest, durationMs;

    ReflectTranscriptController(Activity activity, ReflectView view, ReflectPreferences preferences,
                                ReflectTranscriptStore store, ExecutorService io, Consumer<String> notice,
                                IntConsumer playFrom, Runnable refreshCatalog,
                                ReflectBackendSync backend, ReflectBackendUi backendUi) {
        this.activity = activity;
        this.view = view;
        this.preferences = preferences;
        this.store = store;
        this.io = io;
        this.notice = notice;
        this.playFrom = playFrom;
        this.refreshCatalog = refreshCatalog;
        this.backend = backend;
        this.backendUi = backendUi;
    }

    void open(File recording, String fallback, String length, int durationMs, boolean recordingDetails) {
        int current = ++generation;
        file = recording;
        title = fallback;
        this.length = length;
        this.durationMs = durationMs;
        this.recordingDetails = recordingDetails;
        transcript = null;
        remoteRevision = null;
        metadata = null;
        previewKey = null;
        loading = true;
        issue = null;
        view.transcriptView().speakerNames(Collections.emptyMap());
        render();
        view.show(ReflectView.Screen.TRANSCRIPT);
        io.execute(() -> {
            String resolvedTitle = store.title(recording, fallback);
            RecordingMetadata properties = RecordingMetadata.read(recording);
            try {
                ReflectTranscript loaded = store.load(recording);
                deliver(current, () -> {
                    title = resolvedTitle;
                    metadata = properties;
                    transcript = loaded;
                    loading = false;
                    render();
                    refreshRemote(current, recording);
                });
            } catch (IOException | RuntimeException invalid) {
                deliver(current, () -> {
                    title = resolvedTitle;
                    metadata = properties;
                    issue = "Не удалось прочитать сохранённый транскрипт. Можно импортировать другой JSON.";
                    loading = false;
                    render();
                    refreshRemote(current, recording);
                });
            }
        });
    }

    void openPreview(String key) {
        ++generation;
        file = null;
        previewKey = key;
        title = ReflectDesignPreview.transcriptTitle(key);
        transcript = ReflectDesignPreview.transcript(key);
        remoteRevision = null;
        recordingDetails = false;
        metadata = null;
        length = "";
        loading = false;
        issue = null;
        view.transcriptView().speakerNames(Collections.emptyMap());
        render();
        view.show(ReflectView.Screen.TRANSCRIPT);
    }

    void close() {
        ++generation;
        file = null;
        transcript = null;
        remoteRevision = null;
        metadata = null;
        previewKey = null;
        view.transcriptView().clear();
    }

    void saveState(Bundle state) {
        if (view.screen() != ReflectView.Screen.TRANSCRIPT) return;
        state.putBoolean("transcript", true);
        state.putBoolean("recordingDetails", recordingDetails);
        state.putString("transcriptTitle", title);
        state.putString("transcriptPreview", previewKey);
        if (file != null) state.putString("transcriptAudio", file.getAbsolutePath());
    }

    void pick() {
        if (file == null || loading) {
            if (previewKey != null) notice.accept("Откройте локальную запись, чтобы импортировать транскрипт");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        activity.startActivityForResult(picker, PICK_JSON);
    }

    void exportOptions() {
        if (file == null || loading) {
            notice.accept(previewKey != null ? "Это пример из макета" : "Данные записи ещё загружаются");
            return;
        }
        Sheet menu = sheet("Экспорт записи");
        if (transcript != null && !transcript.lines.isEmpty()) {
            menu.option(RecordingExport.JSON.label, () -> export(RecordingExport.JSON));
            menu.option(RecordingExport.MARKDOWN.label, () -> export(RecordingExport.MARKDOWN));
        }
        menu.option(RecordingExport.AUDIO.label, () -> export(RecordingExport.AUDIO));
        menu.show();
    }

    private void export(RecordingExport format) {
        if (file == null) return;
        activity.startActivityForResult(format.createDocument(metadata, title), format.request);
    }

    boolean onActivityResult(int request, int result, Intent data) {
        RecordingExport format = RecordingExport.forRequest(request);
        if (request != PICK_JSON && format == null) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || file == null) return true;
        File recording = file;
        Uri uri = data.getData();
        int current = generation;
        int duration = durationMs;
        if (request == PICK_JSON) transcriptRequest++;
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
                    boolean queued = backend.onImported(recording, exportTitle, duration, imported);
                    deliver(current, () -> {
                        transcript = imported;
                        remoteRevision = null;
                        loading = false;
                        issue = null;
                        view.transcriptView().speakerNames(Collections.emptyMap());
                        render();
                        notice.accept(!backend.enabled() || queued ? "Транскрипт импортирован"
                                : "Транскрипт сохранён на устройстве. Не удалось добавить его в очередь");
                    });
                } else {
                    ReflectTranscript source = snapshot;
                    if (format != RecordingExport.AUDIO && source == null) {
                        source = backend.cachedTranscript(recording);
                        if (source == null) source = store.load(recording);
                    }
                    try (OutputStream stream = activity.getContentResolver().openOutputStream(uri, "wt")) {
                        if (stream == null) throw new IOException("No export output");
                        format.write(stream, recording, source, exportTitle);
                    }
                    deliver(current, () -> notice.accept("Файл экспортирован"));
                }
            } catch (IOException | RuntimeException error) {
                deliver(current, () -> notice.accept(request == PICK_JSON
                        ? "Не удалось импортировать JSON с репликами" : "Не удалось экспортировать файл"));
            }
        });
        return true;
    }

    void options() {
        Sheet menu = sheet(recordingDetails ? "Действия записи" : "Действия разговора");
        if (file != null) {
            menu.option("Переименовать", () -> rename());
            if (!recordingDetails) menu.option("Импортировать транскрипт", this::pick);
            if (backend.enabled()) menu.option("Отправить запись на сервер", this::sendToBackend);
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
        if (file != null && transcript != null && backend.enabled()) {
            File recording = file;
            ReflectTranscript visible = transcript;
            String revision = remoteRevision;
            int current = generation, request = transcriptRequest;
            menu.option("Привязать спикера", () -> backendUi.linkSpeaker(recording, visible, revision, line.speakerId,
                    () -> current == generation && request == transcriptRequest,
                    () -> { if (current == generation) refreshRemote(current, recording); }));
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
                    backend.onRenamed(recording, newTitle, duration);
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
        view.transcriptView().content(title, length, transcript, previewKey != null, file != null,
                loading, issue, recordingDetails, metadata);
    }

    private void refreshRemote(int current, File recording) {
        if (!backend.enabled()) return;
        int request = ++transcriptRequest;
        backend.pullTranscript(recording, remote -> {
            if (current != generation || request != transcriptRequest) return;
            if (!java.util.Objects.equals(remoteRevision, remote.revision())) {
                view.transcriptView().speakerNames(Collections.emptyMap());
            }
            transcript = remote.value();
            remoteRevision = remote.revision();
            issue = null;
            render();
            refreshSpeakerNames(current, recording);
        });
    }

    private void refreshSpeakerNames(int current, File recording) {
        if (!backend.enabled() || recordingDetails || remoteRevision == null
                || transcript == null || transcript.lines.isEmpty()) return;
        int request = transcriptRequest;
        backendUi.speakerNames(recording, transcript, remoteRevision, names -> {
            if (current == generation && request == transcriptRequest) view.transcriptView().speakerNames(names);
        });
    }

    private void sendToBackend() {
        File recording = file;
        if (recording == null) return;
        String name = title;
        int duration = durationMs, current = generation;
        io.execute(() -> {
            try {
                ReflectTranscript local = store.load(recording);
                boolean queued = local != null ? backend.onImported(recording, name, duration, local)
                        : backend.onSaved(recording, name, duration);
                deliver(current, () -> notice.accept(queued ? "Запись поставлена в очередь"
                        : "Не удалось поставить запись в очередь"));
            } catch (IOException | RuntimeException invalid) {
                deliver(current, () -> notice.accept("Не удалось поставить запись в очередь"));
            }
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
            TextView button = style.text(title, 16, Color.WHITE, style.art.regular);
            button.setMinHeight(style.px(44));
            button.setPadding(style.px(10), 0, style.px(10), 0);
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
