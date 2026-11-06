package com.vexto.reflect;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Existing speaker actions backed only by the private on-device database. */
final class SpeakerProfilesUi {
    private final Activity activity;
    private final ReflectView view;
    private final SpeakerProfiles profiles;
    private final Executor io;
    private final Consumer<String> notice;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed;

    SpeakerProfilesUi(Activity activity, ReflectView view, SpeakerProfiles profiles, Executor io,
                      Consumer<String> notice, Runnable changed) {
        this.activity = activity; this.view = view; this.profiles = profiles;
        this.io = io; this.notice = notice; this.changed = changed;
    }

    void close() { closed = true; }

    void refreshSpeakers() {
        run(profiles::list, entries -> {
            List<ReflectCatalog.Speaker> rows = new ArrayList<>();
            for (var profile : entries) rows.add(new ReflectCatalog.Speaker(profile.id(), profile.name(), "", null));
            view.setSpeakers(rows);
        });
    }

    void addSpeaker() {
        editName("Новый спикер", "", name -> run(() -> profiles.create(name), value -> updated()));
    }

    void manageSpeaker(ReflectCatalog.Speaker speaker) {
        new AlertDialog.Builder(activity).setTitle(speaker.name)
                .setItems(new String[]{"Переименовать", "Удалить"}, (dialog, which) -> {
                    if (which == 0) editName("Имя спикера", speaker.name,
                            name -> run(() -> { profiles.rename(speaker.id, name); return null; }, value -> updated()));
                    else new AlertDialog.Builder(activity).setTitle("Удалить профиль?")
                            .setMessage("Привязки к «" + speaker.name + "» будут сняты. Записи и текст сохранятся.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Удалить", (d, i) -> run(() -> { profiles.delete(speaker.id); return null; }, value -> updated()))
                            .show();
                }).show();
    }

    void linkSpeaker(File recording, ReflectTranscript transcript, String localId,
                     BooleanSupplier active, Runnable linked) {
        run(() -> {
            List<SpeakerProfiles.Profile> available = profiles.list();
            Map<String, Double> scores = new HashMap<>();
            for (var candidate : profiles.suggestions(recording, transcript, localId))
                scores.put(candidate.profile().id(), candidate.score());
            return new Selection(available, profiles.links(recording, transcript).get(localId), scores);
        }, selection -> {
            if (!active.getAsBoolean()) return;
            String[] names = new String[selection.profiles.size() + 2];
            int selected = 0; names[0] = "Без привязки"; names[names.length - 1] = "Новый профиль…";
            for (int i = 0; i < selection.profiles.size(); i++) {
                var profile = selection.profiles.get(i);
                Double score = selection.scores.get(profile.id());
                names[i + 1] = profile.name() + (score == null ? "" : String.format(Locale.ROOT, " · сходство %.2f", score));
                if (profile.id().equals(selection.linked)) selected = i + 1;
            }
            new AlertDialog.Builder(activity).setTitle("Спикер этой записи")
                    .setSingleChoiceItems(names, selected, (dialog, which) -> {
                        dialog.dismiss();
                        if (!active.getAsBoolean()) return;
                        if (which == names.length - 1) {
                            editName("Новый спикер", "", name -> {
                                if (!active.getAsBoolean()) return;
                                run(() -> { var profile = profiles.create(name);
                                    profiles.link(recording, transcript, localId, profile.id()); return null;
                                }, value -> { updated(); if (active.getAsBoolean()) linked.run(); });
                            });
                        } else {
                            String id = which == 0 ? null : selection.profiles.get(which - 1).id();
                            run(() -> { profiles.link(recording, transcript, localId, id); return null; },
                                    value -> { updated(); if (active.getAsBoolean()) linked.run(); });
                        }
                    }).setNegativeButton("Отмена", null).show();
        });
    }

    void speakerNames(File recording, ReflectTranscript transcript, Consumer<Map<String, String>> loaded) {
        run(() -> profiles.names(recording, transcript), loaded);
    }

    private void updated() { refreshSpeakers(); changed.run(); }

    private <T> void run(Query<T> query, Consumer<T> done) {
        if (closed) return;
        io.execute(() -> {
            try {
                T value = query.get();
                main.post(() -> { if (!closed && !activity.isDestroyed()) done.accept(value); });
            } catch (IOException | RuntimeException error) {
                main.post(() -> { if (!closed && !activity.isDestroyed()) notice.accept("Не удалось сохранить или прочитать профиль спикера"); });
            }
        });
    }

    private void editName(String title, String current, Consumer<String> saved) {
        LinearLayout fields = new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(inset, inset / 2, inset, 0);
        EditText name = new EditText(activity); name.setSingleLine(true); name.setHint("Имя");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)}); name.setText(current);
        fields.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(fields)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError("Введите имя спикера"); return; }
            dialog.dismiss(); saved.accept(value);
        }));
        dialog.show();
    }

    private record Selection(List<SpeakerProfiles.Profile> profiles, String linked, Map<String, Double> scores) {}
    private interface Query<T> { T get() throws IOException; }
}
