package com.vexto.reflect;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Settings and user-initiated actions for the server workspace. */
final class ReflectBackendUi {
    private final Activity activity;
    private final ReflectView view;
    private final ReflectBackendSync backend;
    private final Consumer<String> notice;
    private final Runnable configChanged;

    ReflectBackendUi(Activity activity, ReflectView view, ReflectBackendSync backend,
                     Consumer<String> notice, Runnable configChanged) {
        this.activity = activity;
        this.view = view;
        this.backend = backend;
        this.notice = notice;
        this.configChanged = configChanged;
    }

    void settings() {
        LinearLayout fields = fields();
        TextView help = new TextView(activity);
        String description = "Новые записи и импортированные расшифровки будут отправляться на указанный сервер. "
                + "Ранее сохранённые записи можно отправить из их карточек.";
        if (backend.enabled()) description += "\n\nЗаписей в очереди: " + backend.pendingCount();
        help.setText(description);
        fields.addView(help);
        EditText url = input(fields, "Адрес сервера", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(backend.configuredUrl());
        EditText token = input(fields, "Токен рабочего пространства",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setImportantForAutofill(EditText.IMPORTANT_FOR_AUTOFILL_NO);
        token.setSaveEnabled(false);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Подключение к серверу")
                .setView(fields).setNegativeButton("Отмена", null)
                .setNeutralButton("Отключить", (d, which) -> {
                    backend.configure("", "");
                    configChanged.run();
                })
                .setPositiveButton("Подключить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                backend.configure(url.getText().toString().trim(), token.getText().toString().trim());
                dialog.dismiss();
                configChanged.run();
                if (backend.enabled()) {
                    backend.speakers(list -> notice.accept("Сервер подключён"), notice);
                }
            } catch (IllegalArgumentException invalid) {
                url.setError("Проверьте HTTPS-адрес и токен рабочего пространства");
            }
        }));
        dialog.show();
    }

    void refreshSpeakers() {
        if (!backend.enabled()) {
            view.setSpeakers(Collections.emptyList());
            return;
        }
        backend.speakers(speakers -> {
            List<ReflectCatalog.Speaker> rows = new ArrayList<>(speakers.size());
            for (var speaker : speakers) {
                rows.add(new ReflectCatalog.Speaker(speaker.id(), speaker.name(), "", null));
            }
            view.setSpeakers(rows);
        }, error -> {
            if (view.screen() == ReflectView.Screen.SPEAKERS) notice.accept(error);
        });
    }

    void addSpeaker() {
        editName("Новый спикер", "", name -> backend.createSpeaker(name, s -> refreshSpeakers(), notice));
    }

    void manageSpeaker(ReflectCatalog.Speaker speaker) {
        new AlertDialog.Builder(activity).setTitle(speaker.name)
                .setItems(new String[]{"Переименовать", "Удалить"}, (dialog, which) -> {
                    if (which == 0) {
                        editName("Имя спикера", speaker.name, name -> backend.renameSpeaker(speaker.id, name,
                                updated -> refreshSpeakers(), notice));
                    } else {
                        new AlertDialog.Builder(activity).setTitle("Удалить спикера?")
                                .setMessage(speaker.name)
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить", (d, i) -> backend.deleteSpeaker(speaker.id,
                                        this::refreshSpeakers, notice))
                                .show();
                    }
                }).show();
    }

    void linkSpeaker(File recording, ReflectTranscript transcript, String revision, String localId,
                     BooleanSupplier active, Runnable changed) {
        backend.speakers(speakers -> backend.speakerLinks(recording, transcript, revision, state -> {
            if (!active.getAsBoolean()) return;
            String existing = state.links().get(localId);
            String[] names = new String[speakers.size() + 1];
            int selected = 0;
            names[0] = "Без привязки";
            for (int i = 0; i < speakers.size(); i++) {
                var speaker = speakers.get(i);
                names[i + 1] = speaker.name();
                if (speaker.id().equals(existing)) selected = i + 1;
            }
            if (speakers.isEmpty() && existing == null) {
                notice.accept("Сначала добавьте спикера во вкладке «Спикеры»");
                return;
            }
            new AlertDialog.Builder(activity).setTitle("Привязать спикера")
                    .setSingleChoiceItems(names, selected, (dialog, which) -> {
                        dialog.dismiss();
                        if (!active.getAsBoolean()) return;
                        String global = which == 0 ? null : speakers.get(which - 1).id();
                        backend.linkSpeaker(recording, localId, global, state.revision(), changed, notice);
                    })
                    .setNegativeButton("Отмена", null).show();
        }, notice), notice);
    }

    void speakerNames(File recording, ReflectTranscript transcript, String revision,
                      Consumer<Map<String, String>> loaded) {
        backend.speakerLinks(recording, transcript, revision, state -> {
            var links = state.links();
            if (links.isEmpty()) {
                loaded.accept(Collections.emptyMap());
                return;
            }
            backend.speakers(speakers -> {
                Map<String, String> globalNames = new HashMap<>();
                for (var speaker : speakers) globalNames.put(speaker.id(), speaker.name());
                Map<String, String> names = new HashMap<>();
                links.forEach((local, global) -> {
                    String name = globalNames.get(global);
                    if (name != null) names.put(local, name);
                });
                loaded.accept(names);
            }, error -> {});
        }, error -> {});
    }

    private void editName(String title, String value, Consumer<String> saved) {
        LinearLayout fields = fields();
        EditText name = input(fields, "Имя", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        name.setText(value);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(fields)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String input = name.getText().toString().trim();
            if (input.isEmpty()) {
                name.setError("Введите имя спикера");
                return;
            }
            dialog.dismiss();
            saved.accept(input);
        }));
        dialog.show();
    }

    private LinearLayout fields() {
        int inset = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(inset, inset / 2, inset, 0);
        return fields;
    }

    private EditText input(LinearLayout fields, String hint, int type) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(type);
        fields.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }
}
