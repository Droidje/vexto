package com.vexto.reflect;

import android.app.Application;

public final class ReflectApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ReflectEmotionCoordinator.get(this).reconcile();
    }
}
