package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import com.vexto.engine.model.ProcessingMode;

final class ReflectPreferences {
    private final SharedPreferences store;

    ReflectPreferences(Context context) {
        store = context.getSharedPreferences("reflect_preferences", Context.MODE_PRIVATE);
    }

    int textSize() { return store.getInt("transcript_text_size", 16); }
    void setTextSize(int size) { store.edit().putInt("transcript_text_size", size).apply(); }
    boolean timestamps() { return store.getBoolean("transcript_timestamps", true); }
    void setTimestamps(boolean enabled) { store.edit().putBoolean("transcript_timestamps", enabled).apply(); }
    boolean reduceMotion() { return store.getBoolean("reduce_motion", false); }
    boolean motionEnabled() { return !reduceMotion() && ValueAnimator.areAnimatorsEnabled(); }
    void setReduceMotion(boolean enabled) { store.edit().putBoolean("reduce_motion", enabled).apply(); }
    ProcessingMode processingMode() {
        try { return ProcessingMode.valueOf(store.getString("processing_mode", ProcessingMode.AUTO.name())); }
        catch (IllegalArgumentException | NullPointerException invalid) { return ProcessingMode.AUTO; }
    }
    void setProcessingMode(ProcessingMode mode) { store.edit().putString("processing_mode", mode.name()).apply(); }
}
