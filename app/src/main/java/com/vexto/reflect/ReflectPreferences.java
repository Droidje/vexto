package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;

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
}
