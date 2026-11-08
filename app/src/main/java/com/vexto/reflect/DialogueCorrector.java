package com.vexto.reflect;

import android.content.Context;
import com.vexto.engine.punctuation.RuPunctuator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Restores display punctuation only, yielding to other local model work. */
final class DialogueCorrector implements AutoCloseable {
    private final Context context;
    private final Map<String, String> cache = new LinkedHashMap<>(64, .75f, true);
    private volatile boolean closed;
    private boolean unavailable, skipped;

    DialogueCorrector(Context context) {
        this.context = context.getApplicationContext();
    }

    // Called on the dedicated correction executor.
    String restore(String text) {
        return restoreBatch(List.of(text)).getOrDefault(text, text);
    }

    Map<String, String> restoreBatch(List<String> texts) {
        skipped = false;
        Map<String, String> result = new LinkedHashMap<>();
        if (closed || unavailable || texts.isEmpty()) return result;
        List<String> missing = new ArrayList<>();
        for (String text : texts) {
            String cached = cache.get(text);
            if (cached != null) result.put(text, cached);
            else if (!missing.contains(text)) missing.add(text);
        }
        if (missing.isEmpty()) return result;
        try {
            List<String> restored = RuPunctuator.tryRestoreBatch(context, missing);
            if (restored == null) {
                skipped = true;
                return result;
            }
            for (int i = 0; i < restored.size(); i++) {
                String text = missing.get(i);
                String value = restored.get(i);
                cache.put(text, value);
                result.put(text, value);
                if (cache.size() > 4000) cache.remove(cache.keySet().iterator().next());
            }
            skipped = restored.size() < missing.size();
            return result;
        } catch (Exception | LinkageError failure) {
            unavailable = true;
            return result;
        }
    }

    boolean skipped() { return skipped; }

    @Override public void close() { closed = true; }
}
