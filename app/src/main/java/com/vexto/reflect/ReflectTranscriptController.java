package com.vexto.reflect;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
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
    private final ReflectConversationStore conversations;
    private final ReflectConversationTitles titles;
    private final ExecutorService io;
    private final Consumer<String> notice;
    private final IntConsumer playFrom;
    private final Runnable playAudio;
    private final Runnable refreshCatalog;
    private final SpeakerProfiles profiles;
    private final SpeakerProfilesUi profilesUi;
    private final ReflectLocalTranscription local;
    private final ReflectExportController exports;
    private final ReflectSummaryController summaries;
    private final UtteranceRecheckSession rechecks;
    private final ExecutorService correctionIo = Executors.newSingleThreadExecutor();
    private final DialogueCorrector corrector;
    private final ReflectTranscriptionOutcomes outcomes;
    private volatile int correctionSerial;
    private RecordingExport restoreExport;
    private File file;
    private final Set<File> importing = new HashSet<>();
    private ReflectTranscript transcript;
    private RecordingMetadata metadata;
    private String title, length, issue;
    private boolean loading, recordingDetails;
    private int generation, transcriptRequest, durationMs;

    ReflectTranscriptController(Activity activity, ReflectView view, ReflectPreferences preferences,
                                ReflectTranscriptStore store, ReflectConversationStore conversations, ReflectConversationTitles titles,
                                ExecutorService io, Consumer<String> notice,
                                IntConsumer playFrom, Runnable playAudio, Runnable refreshCatalog,
                                SpeakerProfiles profiles, SpeakerProfilesUi profilesUi) {
        this.activity = activity;
        this.view = view;
        this.preferences = preferences;
        this.store = store;
        this.conversations = conversations;
        this.titles = titles;
        this.io = io;
        this.notice = notice;
        this.playFrom = playFrom;
        this.playAudio = playAudio;
        this.refreshCatalog = refreshCatalog;
        this.profiles = profiles;
        this.profilesUi = profilesUi;
        corrector = new DialogueCorrector(activity);
        outcomes = new ReflectTranscriptionOutcomes(activity);
        summaries = new ReflectSummaryController(activity, store, titles, io, view.transcriptView()::summary);
        rechecks = new UtteranceRecheckSession(activity, store, profiles, io);
        exports = new ReflectExportController(activity, view, io, profiles, notice);
        local = new ReflectLocalTranscription(activity, this::processingChanged, this::transcriptionFinished);
    }

    void open(File recording, String fallback, String length, int durationMs, boolean recordingDetails,
              boolean showProcessing, boolean startImmediately) {
        int current = ++generation;
        rechecks.cancel();
        view.transcriptView().rechecking(null);
        file = recording;
        title = fallback;
        this.length = length;
        this.durationMs = durationMs;
        this.recordingDetails = recordingDetails;
        transcript = null;
        metadata = null;
        view.transcriptView().corrections(Collections.emptyMap());
        loading = true;
        issue = outcomes.failure(recording);
        view.transcriptView().waveform(null);
        view.transcriptView().speakerNames(Collections.emptyMap());
        render();
        var currentProgress = local.progress(recording);
        boolean processing = showProcessing && (startImmediately || currentProgress != null && !currentProgress.backgroundEmotions());
        if (processing) view.processingView().content(length, local.progress(recording));
        view.show(processing ? ReflectView.Screen.PROCESSING : ReflectView.Screen.TRANSCRIPT);
        // Start the foreground service while the activity is visible, before loading metadata on IO.
        if (startImmediately && !local.start(recording)) showTranscriptAfterProcessing();
        io.execute(() -> {
            String resolvedTitle = resolveTitle(recording, fallback, recordingDetails);
            RecordingMetadata properties = RecordingMetadata.read(recording);
            try {
                ReflectTranscript loaded = store.load(recording);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || current != generation) return;
                    title = resolvedTitle;
                    metadata = properties;
                    if (transcript == null) transcript = loaded;
                    loading = false;
                    render();
                    refreshSpeakerNames(current, recording);
                    if (local.progress(recording) == null) showTranscriptAfterProcessing();
                });
            } catch (IOException | RuntimeException invalid) {
                String readIssue = "Не удалось прочитать сохранённый транскрипт. Можно импортировать другой JSON.";
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || current != generation) return;
                    title = resolvedTitle;
                    metadata = properties;
                    if (transcript == null) issue = readIssue;
                    loading = false;
                    render();
                    if (local.progress(recording) == null) showTranscriptAfterProcessing();
                    refreshSpeakerNames(current, recording);
                });
            }
        });
    }

    private String resolveTitle(File recording, String fallback, boolean recordingDetails) {
        if (recordingDetails) return store.title(recording, fallback);
        try { return conversations.title(recording); }
        catch (RuntimeException unavailable) { return fallback; }
    }

    void close() {
        ++generation;
        ++correctionSerial;
        rechecks.cancel();
        file = null;
        transcript = null;
        metadata = null;
        summaries.content(null, null);
        view.transcriptView().clear();
    }

    void resumeProcessing() {
        local.resume();
        File active = local.activeRecording();
        if (active != null) processingChanged(active);
        else titles.backendBusy(false);
    }

    void destroy() {
        ++correctionSerial;
        summaries.destroy();
        local.close();
        correctionIo.shutdownNow();
        corrector.close();
    }

    void toggleSummary() { summaries.toggle(); }
    void retrySummary() { summaries.retry(); }

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

    boolean canStartBackground() {
        if (!local.busy()) return true;
        notice.accept("Дождитесь завершения текущей обработки.");
        return false;
    }

    void startBackground(File recording) {
        if (!canStartBackground() || importing.contains(recording)) return;
        if (local.start(recording)) notice.accept("Расшифровка запущена в фоне");
    }

    void openRecordingTranscript() {
        var progress = local.progress(file);
        if (file == null || loading || !recordingDetails || progress != null && !progress.backgroundEmotions()
                || transcript == null || transcript.lines.isEmpty()) return;
        File recording = file;
        int current = generation;
        io.execute(() -> {
            try {
                conversations.restore(recording);
                String conversationTitle = conversations.title(recording);
                deliver(current, () -> {
                    open(recording, conversationTitle, length, durationMs, false, false, false);
                    refreshCatalog.run();
                });
            } catch (RuntimeException failure) {
                deliver(current, () -> notice.accept("Не удалось открыть транскрипт"));
            }
        });
    }

    void cancelTranscription() {
        local.cancel(file);
        if (local.progress(file) == null) showTranscriptAfterProcessing();
    }

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
        ReflectActionSheet menu = sheet(recordingDetails ? "Действия записи" : "Действия разговора");
        if (file != null) {
            if (!recordingDetails && transcript != null && !transcript.lines.isEmpty()) {
                menu.option(view.transcriptView().isPlaying() ? "Приостановить" : "Прослушать аудио", playAudio);
            }
            menu.option("Переименовать", this::rename);
            if (!recordingDetails) menu.option("Импортировать транскрипт", this::pick);
        }
        if (!recordingDetails) menu.option("Настройки чтения", this::styleOptions);
        menu.show();
    }

    void styleOptions() {
        ReflectActionSheet menu = sheet("Размер текста");
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

    void recheck(ReflectTranscript.Line line) {
        if (recordingDetails || file == null || transcript == null || line == null) return;
        if (local.busy()) {
            notice.accept("Дождитесь окончания расшифровки");
            return;
        }
        File recording = file;
        rechecks.start(recording, transcript, line, generation, new UtteranceRecheckSession.Host() {
            @Override public boolean current(int token) {
                return token == generation && !activity.isDestroyed();
            }

            @Override public void shimmer(ReflectTranscript.Line value) {
                view.transcriptView().rechecking(value);
            }

            @Override public void idle() { view.transcriptView().rechecking(null); }

            @Override public void show(ReflectTranscript value) {
                if (!recording.equals(file)) return;
                transcript = value;
                view.transcriptView().rechecking(null);
                render();
                refreshSpeakerNames(generation, recording);
                refreshCatalog.run();
            }

            @Override public void message(String text) { notice.accept(text); }
        });
    }

    void lineOptions(ReflectTranscript.Line line) {
        ReflectActionSheet menu = sheet("Реплика");
        String shown = view.transcriptView().shownText(line);
        TextView excerpt = menu.style.text(shown, 15, ReflectPageStyle.MUTED, menu.style.art.regular);
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
                clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.app_name), shown));
            }
        });
        menu.option("Поделиться репликой", () -> activity.startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shown),
                "Поделиться репликой")));
        menu.show();
    }

    private void rename() {
        File recording = file;
        if (recording == null) return;
        boolean isRecording = recordingDetails;
        ReflectActionSheet editor = sheet(isRecording ? "Название записи" : "Название разговора");
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
                notice.accept("Введите название");
                return;
            }
            int current = generation;
            io.execute(() -> {
                try {
                    if (isRecording) store.rename(recording, newTitle);
                    else if (!conversations.rename(recording, newTitle)) return;
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed()) refreshCatalog.run();
                    });
                    deliver(current, () -> {
                        title = newTitle;
                        render();
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

    void requestDeleteConversation(Runnable closed) {
        if (file == null || loading || recordingDetails) return;
        File recording = file;
        int current = generation;
        ReflectActionSheet confirmation = sheet("Удалить разговор?");
        TextView description = confirmation.style.text("Разговор исчезнет из списка. Аудиозапись останется в «Записях».",
                15, ReflectPageStyle.MUTED, confirmation.style.art.regular);
        confirmation.style.append(confirmation.root, description, 0, 12);
        confirmation.option("Отмена", () -> {});
        confirmation.option("Удалить разговор", ReflectPageStyle.DANGER, () -> {
            summaries.cancel(recording);
            io.execute(() -> {
                try {
                    if (!conversations.delete(recording)) return;
                    try { store.deleteSummary(recording); }
                    catch (IOException ignored) { /* The conversation is already deleted. */ }
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed()) refreshCatalog.run();
                    });
                    deliver(current, closed);
                } catch (RuntimeException failure) {
                    deliver(current, () -> notice.accept("Не удалось удалить разговор"));
                }
            });
        });
        confirmation.show();
    }

    void titleChanged(File recording) {
        if (recordingDetails || !recording.equals(file)) return;
        int current = generation;
        io.execute(() -> {
            String updated = resolveTitle(recording, "Разговор", false);
            deliver(current, () -> {
                title = updated;
                render();
            });
        });
    }

    private void render() {
        view.transcriptView().failedTranscription(file != null && outcomes.failure(file) != null);
        view.transcriptView().content(title, length, durationMs, transcript, file != null,
                loading, issue, recordingDetails, metadata, local.progress(file));
        summaries.content(recordingDetails ? null : file, transcript);
        restoreExportIfReady();
        scheduleCorrection();
    }

    private void scheduleCorrection() {
        int serial = ++correctionSerial;
        int current = generation;
        ReflectTranscript source = transcript;
        File recording = file;
        String mode = preferences.punctuation();
        if (source == null || source.lines.isEmpty() || recordingDetails
                || ReflectPreferences.PUNCTUATION_OFF.equals(mode)) {
            view.transcriptView().corrections(Collections.emptyMap());
            return;
        }
        io.execute(() -> {
            Set<String> mine = new HashSet<>();
            if (ReflectPreferences.PUNCTUATION_SELF.equals(mode)) {
                try {
                    Set<String> self = new HashSet<>();
                    for (var profile : profiles.list()) if (profile.self()) self.add(profile.id());
                    profiles.links(recording, source).forEach((local, global) -> {
                        if (self.contains(global)) mine.add(local);
                    });
                } catch (IOException | RuntimeException unavailable) {
                    return;
                }
            }
            if (serial != correctionSerial || correctionIo.isShutdown()) return;
            correctionIo.execute(() -> {
                Map<Integer, String> corrected = new HashMap<>();
                boolean retry = false;
                List<Integer> selected = new ArrayList<>();
                for (int index = 0; index < source.lines.size(); index++) {
                    var line = source.lines.get(index);
                    if (CorrectorSelection.applies(mode, mine.contains(line.speakerId))) selected.add(index);
                }
                for (int start = 0; start < selected.size(); start += 16) {
                    if (serial != correctionSerial || Thread.currentThread().isInterrupted()) return;
                    var batch = selected.subList(start, Math.min(start + 16, selected.size()));
                    List<String> texts = new ArrayList<>(batch.size());
                    for (int index : batch) texts.add(source.lines.get(index).text);
                    Map<String, String> restored = corrector.restoreBatch(texts);
                    for (int index : batch) {
                        String original = source.lines.get(index).text;
                        String value = restored.get(original);
                        if (value != null && !value.equals(original)) corrected.put(index, value);
                    }
                    if (corrector.skipped()) {
                        retry = true;
                        break;
                    }
                }
                boolean pending = retry;
                deliver(current, () -> {
                    if (serial != correctionSerial || transcript != source) return;
                    view.transcriptView().corrections(corrected);
                    if (pending) view.postDelayed(() -> {
                        if (serial == correctionSerial && transcript == source) scheduleCorrection();
                    }, 1_200);
                });
            });
        });
    }

    private void processingChanged(File recording) {
        titles.backendBusy(local.busy());
        view.updateLibraryProgress(recording, local.progress(recording));
        if (!recording.equals(file)) return;
        var progress = local.progress(file);
        if (view.screen() == ReflectView.Screen.PROCESSING) {
            if (progress == null || progress.backgroundEmotions() && transcript != null) {
                showTranscriptAfterProcessing();
                view.transcriptView().transcription(progress);
            }
            else view.processingView().content(length, progress);
        } else view.transcriptView().transcription(progress);
    }

    private void transcriptionFinished(ReflectLocalTranscription.Result result) {
        titles.backendBusy(local.busy());
        view.updateLibraryProgress(result.recording(), local.progress(result.recording()));
        if (result.recording().equals(file)) {
            if (result.transcript() != null) {
                transcript = result.transcript();
                issue = null;
                refreshSpeakerNames(generation, file);
            } else if (transcript == null) {
                issue = result.message();
            }
            render();
            showTranscriptAfterProcessing();
        }
        if ((!result.metadataOnly() || result.message() != null)
                && (result.transcript() != null || !result.recording().equals(file) || result.metadataOnly())) {
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
        if (transcript == null || transcript.lines.isEmpty()) return;
        int request = ++transcriptRequest;
        profilesUi.speakerNames(recording, transcript, names -> {
            if (current == generation && request == transcriptRequest) {
                view.transcriptView().speakerNames(names);
                scheduleCorrection();
            }
        });
    }

    private void deliver(int current, Runnable result) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed() && current == generation) result.run();
        });
    }

    private ReflectActionSheet sheet(String title) {
        return view.actionSheet(activity, title);
    }
}
