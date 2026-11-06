package com.vexto.reflect;

import android.content.Context;
import android.content.SharedPreferences;

final class ReflectPreferences {
    private final SharedPreferences store;

    ReflectPreferences(Context context) {
        store = context.getSharedPreferences("reflect_preferences", Context.MODE_PRIVATE);
    }

    boolean reduceMotion() { return store.getBoolean("reduce_motion", false); }
    void setReduceMotion(boolean enabled) { store.edit().putBoolean("reduce_motion", enabled).apply(); }
}
