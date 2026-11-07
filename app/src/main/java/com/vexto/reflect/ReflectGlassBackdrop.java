package com.vexto.reflect;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;

/** Draws a filtered copy of the lower layers inside one glass surface. */
final class ReflectGlassBackdrop {
    @FunctionalInterface interface Source {
        void draw(Canvas canvas);
    }

    private static final int BACKGROUND = 0xff040402;
    private final Path shape = new Path();
    private Gpu gpu;
    private Cpu cpu;

    void replay(Canvas target, float left, float top, float right, float bottom,
                float corner, float radius) {
        draw(target, left, top, right, bottom, corner, radius, null);
    }

    void draw(Canvas target, float left, float top, float right, float bottom,
              float corner, float radius, Source source) {
        if (right <= left || bottom <= top) return;

        // Sample beyond the visible edge so the blur includes neighboring pixels.
        float padding = radius * 3;
        int x = (int) Math.floor(left - padding);
        int y = (int) Math.floor(top - padding);
        int width = (int) Math.ceil(right + padding) - x;
        int height = (int) Math.ceil(bottom + padding) - y;
        shape.rewind();
        shape.addRoundRect(left, top, right, bottom, corner, corner, Path.Direction.CW);
        int save = target.save();
        target.clipPath(shape);
        if (Build.VERSION.SDK_INT >= 31 && target.isHardwareAccelerated()) {
            if (source != null) {
                if (gpu == null) gpu = new Gpu();
                gpu.capture(x, y, width, height, radius, source);
            }
            if (gpu != null) target.drawRenderNode(gpu.node);
        } else {
            if (source != null) {
                if (cpu == null) cpu = new Cpu();
                cpu.capture(x, y, width, height, radius, source);
            }
            if (cpu != null) cpu.draw(target);
        }
        target.restoreToCount(save);
    }

    @TargetApi(31)
    private static final class Gpu {
        private final RenderNode node = new RenderNode("Vexto glass");
        private float previousRadius;

        void capture(int x, int y, int width, int height, float radius, Source source) {
            node.setPosition(x, y, x + width, y + height);
            if (radius != previousRadius) {
                node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
                previousRadius = radius;
            }
            Canvas recording = node.beginRecording();
            try {
                recording.drawColor(BACKGROUND);
                recording.translate(-x, -y);
                source.draw(recording);
            } finally {
                node.endRecording();
            }
        }
    }

    private static final class Cpu {
        private static final int SAMPLE = 4;
        private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF destination = new RectF();
        private Bitmap bitmap;
        private Canvas recording;
        private int[] pixels, scratch;

        void capture(int x, int y, int width, int height, float radius, Source source) {
            int w = (width + SAMPLE - 1) / SAMPLE;
            int h = (height + SAMPLE - 1) / SAMPLE;
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                recording = new Canvas(bitmap);
                pixels = new int[w * h];
                scratch = new int[w * h];
            }
            int save = recording.save();
            recording.drawColor(BACKGROUND);
            recording.scale((float) w / width, (float) h / height);
            recording.translate(-x, -y);
            source.draw(recording);
            recording.restoreToCount(save);

            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            int kernel = Math.max(1, Math.round(radius / SAMPLE));
            // Two passes per axis approximate a smooth blur with work linear in pixels.
            for (int pass = 0; pass < 2; pass++) {
                blurAxis(pixels, scratch, h, w, w, 1, kernel);
                blurAxis(scratch, pixels, w, h, 1, w, kernel);
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            destination.set(x, y, x + width, y + height);
        }

        void draw(Canvas target) {
            target.drawBitmap(bitmap, null, destination, filter);
        }

        private static void blurAxis(int[] input, int[] output, int lines, int length,
                                     int lineStride, int pixelStride, int radius) {
            int divisor = radius * 2 + 1;
            for (int line = 0; line < lines; line++) {
                int start = line * lineStride;
                int red = 0, green = 0, blue = 0;
                for (int offset = -radius; offset <= radius; offset++) {
                    int color = input[start + clamp(offset, length) * pixelStride];
                    red += (color >> 16) & 255;
                    green += (color >> 8) & 255;
                    blue += color & 255;
                }
                for (int index = 0; index < length; index++) {
                    output[start + index * pixelStride] = 0xff000000 | ((red / divisor) << 16)
                            | ((green / divisor) << 8) | (blue / divisor);
                    int leave = input[start + clamp(index - radius, length) * pixelStride];
                    int enter = input[start + clamp(index + radius + 1, length) * pixelStride];
                    red += ((enter >> 16) & 255) - ((leave >> 16) & 255);
                    green += ((enter >> 8) & 255) - ((leave >> 8) & 255);
                    blue += (enter & 255) - (leave & 255);
                }
            }
        }

        private static int clamp(int index, int length) {
            return Math.max(0, Math.min(index, length - 1));
        }
    }
}
