package com.vexto.reflect;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared rounded menu for recording, conversation and speaker actions. */
final class ReflectActionSheet {
    final Dialog dialog;
    final ReflectPageStyle style;
    final LinearLayout root;
    private final ReflectPreferences preferences;

    ReflectActionSheet(Activity activity, ReflectPageStyle style, ReflectPreferences preferences, String title) {
        this.style = style;
        this.preferences = preferences;
        dialog = new Dialog(activity);
        root = style.column();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        root.setPadding(style.px(16), style.px(16), style.px(16), style.px(12));
        root.setBackground(style.rounded(ReflectPageStyle.CARD, 24));
        style.append(root, style.text(title, 18, Color.WHITE, style.art.semibold), 0, 12);
        dialog.setContentView(root);
    }

    void option(String title, Runnable action) {
        TextView button = style.text(title, 16, Color.WHITE, style.art.regular);
        button.setMinHeight(style.px(44));
        button.setPadding(style.px(10), 0, style.px(10), 0);
        button.setBackground(style.pressable(0xff303030, 14));
        button.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
        style.append(root, button, 4, 0);
    }

    void show() {
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.CENTER);
        window.setWindowAnimations(0);
        boolean animate = preferences.motionEnabled();
        if (animate) {
            root.setAlpha(0);
            root.setTranslationY(style.px(18));
            root.setScaleX(.97f);
            root.setScaleY(.97f);
        }
        dialog.show();
        window.setLayout(style.px(361), WindowManager.LayoutParams.WRAP_CONTENT);
        if (animate) {
            root.animate().alpha(1).translationY(0).scaleX(1).scaleY(1)
                    .setDuration(280).setInterpolator(ReflectMotion.EASE).start();
        }
    }
}
