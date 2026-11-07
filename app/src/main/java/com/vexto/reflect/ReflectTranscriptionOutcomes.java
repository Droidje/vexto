package com.vexto.reflect;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;

/** Terminal failures survive reopening, independently of a pending job or reader load errors. */
final class ReflectTranscriptionOutcomes {
    private final SharedPreferences store;

    ReflectTranscriptionOutcomes(Context context) {
        store = context.getSharedPreferences("transcription_outcomes", Context.MODE_PRIVATE);
    }

    String failure(File recording) { return store.getString(recording.getName(), null); }

    void clear(File recording) throws IOException {
        if (!store.edit().remove(recording.getName()).commit()) {
            throw new IOException("Cannot clear recording outcome");
        }
    }

    void finish(File recording, boolean hasTranscript, String message) {
        var edit = store.edit();
        if (hasTranscript) edit.remove(recording.getName());
        else if (message != null) edit.putString(recording.getName(), message);
        edit.apply();
    }
}
