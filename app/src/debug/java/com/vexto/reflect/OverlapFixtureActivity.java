package com.vexto.reflect;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;

/** Debug-only UI probe: imports the real isolated overlap worker's saved result. */
public final class OverlapFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            File probe = new File(getExternalFilesDir(null), "overlap-eval");
            try {
                File target = new File(getFilesDir(), "recordings/overlap-evaluation.wav");
                if (target.exists()) throw new IllegalStateException("Evaluation recording already exists");
                ReflectTranscript transcript;
                try (FileInputStream reader = new FileInputStream(new File(probe, "transcript.metadata.json"))) {
                    transcript = ReflectTranscript.parse(reader);
                }
                target.getParentFile().mkdirs();
                Files.copy(new File(probe, "input.wav").toPath(), target.toPath());
                new ReflectTranscriptStore(getFilesDir()).save(target, transcript);
                try (ReflectConversationStore store = new ReflectConversationStore(this)) {
                    store.title(target);
                    store.rename(target, "Проверка перебиваний");
                }
                runOnUiThread(() -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
            } catch (Exception error) {
                try { Files.write(new File(probe, "ui-error.txt").toPath(), error.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                catch (Exception ignored) { }
                runOnUiThread(this::finish);
            }
        }, "overlap-ui-probe").start();
    }
}
