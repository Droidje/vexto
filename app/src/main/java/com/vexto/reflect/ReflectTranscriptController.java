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
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Connects the conversation page to private storage and the system document picker. */
final class ReflectTranscriptController {
    private static final int PICK_JSON = 3, EXPORT_JSON = 4;
    private final Activity activity;
    private final ReflectView view;
    private final ReflectPreferences preferences;
    private final ReflectTranscriptStore store;
    private final ExecutorService io;
    private final Consumer<String> notice;
    private final IntConsumer playFrom;
    private final Runnable refreshCatalog;
    private File file;
    private ReflectTranscript transcript;
    private String title, length, issue;
    private String previewKey;
    private boolean loading;
    private int generation;

    ReflectTranscriptController(Activity activity, ReflectView view, ReflectPreferences preferences,
                                ReflectTranscriptStore store, ExecutorService io, Consumer<String> notice,
                                IntConsumer playFrom, Runnable refreshCatalog) {
        this.activity = activity;
        this.view = view;
        this.preferences = preferences;
        this.store = store;
        this.io = io;
        this.notice = notice;
        this.playFrom = playFrom;
        this.refreshCatalog = refreshCatalog;
    }

    void open(File recording, String fallback, String length) {
        int current = ++generation;
        file = recording;
        title = fallback;
        this.length = length;
        transcript = null;
        previewKey = null;
        loading = true;
        issue = null;
        render();
        view.show(ReflectView.Screen.TRANSCRIPT);
        io.execute(() -> {
            String resolvedTitle = store.title(recording, fallback);
            try {
                ReflectTranscript loaded = store.load(recording);
                deliver(current, () -> {
                    title = resolvedTitle;
                    transcript = loaded;
                    loading = false;
                    render();
                });
            } catch (IOException | RuntimeException invalid) {
                deliver(current, () -> {
                    title = resolvedTitle;
                    issue = "Не удалось прочитать сохранённый транскрипт. Можно импортировать другой JSON.";
                    loading = false;
                    render();
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
        length = "";
        loading = false;
        issue = null;
        render();
        view.show(ReflectView.Screen.TRANSCRIPT);
    }

    void close() {
        ++generation;
        file = null;
        transcript = null;
        previewKey = null;
    }

    void saveState(Bundle state) {
        if (view.screen() != ReflectView.Screen.TRANSCRIPT) return;
        state.putBoolean("transcript", true);
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

    void export() {
        if (file == null || transcript == null || loading) {
            notice.accept(previewKey != null ? "Это пример из макета" : "Нет транскрипта для экспорта");
            return;
        }
        Intent output = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "reflect-transcript.json");
        activity.startActivityForResult(output, EXPORT_JSON);
    }

    boolean onActivityResult(int request, int result, Intent data) {
        if (request != PICK_JSON && request != EXPORT_JSON) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || file == null) return true;
        File recording = file;
        Uri uri = data.getData();
        int current = generation;
        ReflectTranscript snapshot = transcript;
        io.execute(() -> {
            try {
                if (request == PICK_JSON) {
                    ReflectTranscript imported;
                    try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IOException("No transcript input");
                        imported = ReflectTranscript.parse(input);
                    }
                    store.save(recording, imported);
                    deliver(current, () -> {
                        transcript = imported;
                        loading = false;
                        issue = null;
                        render();
                        notice.accept("Транскрипт импортирован");
                    });
                } else {
                    ReflectTranscript source = snapshot == null ? store.load(recording) : snapshot;
                    if (source == null) throw new IOException("No transcript to export");
                    byte[] json = source.encode();
                    try (OutputStream stream = activity.getContentResolver().openOutputStream(uri, "wt")) {
                        if (stream == null) throw new IOException("No transcript output");
                        stream.write(json);
                    }
                    deliver(current, () -> notice.accept("Транскрипт экспортирован"));
                }
            } catch (IOException | RuntimeException error) {
                deliver(current, () -> notice.accept(request == PICK_JSON
                        ? "Не удалось импортировать JSON с репликами" : "Не удалось экспортировать транскрипт"));
            }
        });
        return true;
    }

    void options() {
        Sheet menu = sheet("Действия разговора");
        if (file != null) {
            menu.option("Переименовать", () -> rename());
            menu.option("Импортировать транскрипт", this::pick);
            if (transcript != null) menu.option("Экспортировать JSON", this::export);
        }
        menu.option("Настройки чтения", this::styleOptions);
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
        menu.option("Скопировать текст", () -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Reflect", line.text));
        });
        menu.option("Поделиться репликой", () -> activity.startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, line.text),
                "Поделиться репликой")));
        menu.show();
    }

    private void rename() {
        File recording = file;
        if (recording == null) return;
        Sheet editor = sheet("Название разговора");
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
        view.transcriptView().content(title, length, transcript, previewKey != null, file != null, loading, issue);
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
