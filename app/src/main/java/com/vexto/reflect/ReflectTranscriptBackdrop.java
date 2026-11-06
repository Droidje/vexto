package com.vexto.reflect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import androidx.annotation.RequiresApi;

/** Glass behind the transcript tools, redrawn as the visible list scrolls. */
final class ReflectTranscriptBackdrop extends View {
    private static final int SURFACE = 0xff040402;
    private final View content;
    private final Blur blur;

    ReflectTranscriptBackdrop(Context context, View content, float radius) {
        super(context);
        this.content = content;
        blur = Build.VERSION.SDK_INT >= 31 ? new Blur(radius) : null;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00040402, 0xf0040402, SURFACE}));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (Build.VERSION.SDK_INT < 31 || blur == null || !canvas.isHardwareAccelerated()
                || getWidth() == 0 || getHeight() == 0) return;
        blur.draw(canvas, content, getTop(), getWidth(), getHeight());
        canvas.drawColor(0x03ffffff);
    }

    @RequiresApi(31)
    private static final class Blur {
        private final RenderNode strip = new RenderNode("Transcript tools backdrop");

        Blur(float radius) {
            strip.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }

        void draw(Canvas target, View content, int top, int width, int height) {
            strip.setPosition(0, 0, width, height);
            Canvas canvas = strip.beginRecording(width, height);
            try {
                canvas.drawColor(SURFACE);
                canvas.translate(content.getLeft(), content.getTop() - top);
                content.draw(canvas);
            } finally {
                strip.endRecording();
            }
            target.drawRenderNode(strip);
        }
    }
}
