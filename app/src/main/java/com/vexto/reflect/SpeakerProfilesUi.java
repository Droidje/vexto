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
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Speaker directory navigation and actions backed by local recordings and profiles. */
final class SpeakerProfilesUi {
    private final Activity activity;
    private final ReflectView view;
    private final SpeakerProfiles profiles;
    private final SpeakerDirectory directory;
    private final Executor io;
    private final Consumer<String> notice;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed;
    private String profileId;
    private SpeakerDirectory.Detail detail;
    private int listGeneration;
    private int detailGeneration;

    SpeakerProfilesUi(Activity activity, ReflectView view, SpeakerProfiles profiles, SpeakerDirectory directory, Executor io,
                      Consumer<String> notice, Runnable changed) {
        this.activity = activity; this.view = view; this.profiles = profiles; this.directory = directory;
        this.io = io; this.notice = notice; this.changed = changed;
    }

    void close() { closed = true; }

    void refreshSpeakers() {
        int request = ++listGeneration;
        run(directory::list, () -> request == listGeneration, entries -> {
            List<ReflectCatalog.Speaker> rows = new ArrayList<>();
            for (var entry : entries) {
                var profile = entry.profile();
                rows.add(new ReflectCatalog.Speaker(profile.id(), profile.name(), speechMinutes(entry.speechMs()),
                        null, profile.self()));
            }
            view.setSpeakers(rows);
        });
    }

    private static String speechMinutes(long speechMs) {
        long minutes = speechMs / 60_000;
        long lastTwo = minutes % 100;
        String unit = lastTwo >= 11 && lastTwo <= 14 ? "минут" : switch ((int) (minutes % 10)) {
            case 1 -> "минута";
            case 2, 3, 4 -> "минуты";
            default -> "минут";
        };
        return minutes + " " + unit;
    }

    void addSpeaker() {
        editName("Новый спикер", "", name -> run(() -> profiles.create(name), value -> updated()));
    }

    String profileId() { return profileId; }

    void selectProfile(String id) {
        if (Objects.equals(profileId, id)) return;
        detailGeneration++;
        profileId = id;
        detail = null;
        view.speakerProfileView().content(null);
    }

    void openProfile(String id) {
        selectProfile(id);
        view.show(ReflectView.Screen.SPEAKER_PROFILE);
        refreshProfile();
    }

    void closeProfile() {
        view.show(ReflectView.Screen.SPEAKERS);
        selectProfile(null);
    }

    private boolean isOpen(String id) {
        return id.equals(profileId) && view.screen() == ReflectView.Screen.SPEAKER_PROFILE;
    }

    private void refreshProfile() {
        String id = profileId;
        if (id == null || !isOpen(id)) return;
        int request = ++detailGeneration;
        run(() -> {
            try { return directory.detail(id); }
            catch (IOException | RuntimeException unavailable) { return null; }
        }, () -> request == detailGeneration && isOpen(id), next -> {
            if (next == null) {
                closeProfile();
                notice.accept("Не удалось открыть профиль спикера");
                return;
            }
            detail = next;
            view.speakerProfileView().content(next);
        });
    }

    void openMenu() {
        if (detail == null || !isOpen(detail.profile().id())) return;
        var profile = detail.profile();
        ReflectActionSheet menu = view.actionSheet(activity, ReflectCatalog.speakerName(profile.name(), profile.self()));
        menu.option("Переименовать", () -> renameProfile(profile));
        menu.option("Объединить с другим спикером", () -> {
            if (isOpen(profile.id())) chooseMergeTarget(profile);
        });
        if (!profile.self()) {
            menu.option("Отметить как «Я»", () -> {
                if (isOpen(profile.id())) run(() -> {
                    profiles.markSelf(profile.id());
                    return null;
                }, ignored -> updated());
            });
        }
        menu.show();
    }

    void renameProfile() {
        if (detail != null) renameProfile(detail.profile());
    }

    private void renameProfile(SpeakerProfiles.Profile profile) {
        if (!isOpen(profile.id())) return;
        editName("Имя спикера", profile.name(), name -> {
            if (isOpen(profile.id())) run(() -> {
                profiles.rename(profile.id(), name);
                return null;
            }, ignored -> updated());
        });
    }

    private void chooseMergeTarget(SpeakerProfiles.Profile source) {
        run(() -> {
            List<SpeakerProfiles.Profile> targets = new ArrayList<>();
            for (var profile : profiles.visibleProfiles()) if (!source.id().equals(profile.id())) targets.add(profile);
            return targets;
        }, () -> isOpen(source.id()), targets -> {
            if (targets.isEmpty()) {
                notice.accept("Добавьте другого спикера, чтобы объединить профили");
                return;
            }
            String[] names = targets.stream().map(p -> p.name() + (p.self() ? " · Я" : "")).toArray(String[]::new);
            new AlertDialog.Builder(activity).setTitle("Выберите спикера")
                    .setItems(names, (dialog, which) -> confirmMerge(source, targets.get(which)))
                    .setNegativeButton("Отмена", null).show();
        });
    }

    private void confirmMerge(SpeakerProfiles.Profile source, SpeakerProfiles.Profile target) {
        if (!isOpen(source.id())) return;
        new AlertDialog.Builder(activity).setTitle("Объединить спикеров?")
                .setMessage("Все реплики и голосовые образцы «" + source.name() + "» будут связаны с профилем «"
                        + target.name() + "».")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Объединить", (dialog, which) -> {
                    if (!isOpen(source.id())) return;
                    run(() -> {
                        profiles.merge(source.id(), target.id());
                        return null;
                    }, ignored -> {
                        if (source.id().equals(profileId)) selectProfile(target.id());
                        updated();
                    });
                }).show();
    }

    void linkSpeaker(File recording, ReflectTranscript transcript, String localId,
                     BooleanSupplier active, Runnable linked) {
        run(() -> {
            String selected = profiles.assign(recording, transcript).get(localId);
            List<SpeakerProfiles.Profile> available = profiles.visibleProfiles();
            Map<String, Double> scores = new HashMap<>();
            for (var candidate : profiles.suggestions(recording, transcript, localId))
                scores.put(candidate.profile().id(), candidate.score());
            return new Selection(available, selected, scores);
        }, active, selection -> {
            String[] names = new String[selection.profiles.size() + 2];
            int selected = -1;
            names[0] = "Отдельный спикер";
            names[names.length - 1] = "Новый профиль…";
            for (int i = 0; i < selection.profiles.size(); i++) {
                var profile = selection.profiles.get(i);
                Double score = selection.scores.get(profile.id());
                names[i + 1] = profile.name() + (profile.self() ? " · Я" : "")
                        + (score == null ? "" : String.format(Locale.ROOT, " · сходство %.2f", score));
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
                            run(() -> {
                                if (which == 0) profiles.separate(recording, transcript, localId);
                                else profiles.link(recording, transcript, localId, selection.profiles.get(which - 1).id());
                                return null;
                            }, value -> { updated(); if (active.getAsBoolean()) linked.run(); });
                        }
                    }).setNegativeButton("Отмена", null).show();
        });
    }

    void speakerNames(File recording, ReflectTranscript transcript, Consumer<Map<String, String>> loaded) {
        run(() -> {
            profiles.assign(recording, transcript);
            return profiles.names(recording, transcript);
        }, loaded);
    }

    private void updated() {
        refreshSpeakers();
        refreshProfile();
        changed.run();
    }

    private <T> void run(Query<T> query, Consumer<T> done) {
        run(query, () -> true, done);
    }

    private <T> void run(Query<T> query, BooleanSupplier active, Consumer<T> done) {
        if (closed) return;
        io.execute(() -> {
            try {
                T value = query.get();
                main.post(() -> { if (!closed && !activity.isDestroyed() && active.getAsBoolean()) done.accept(value); });
            } catch (IOException | RuntimeException error) {
                main.post(() -> {
                    if (!closed && !activity.isDestroyed() && active.getAsBoolean()) {
                        notice.accept("Не удалось сохранить или прочитать профиль спикера");
                    }
                });
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
