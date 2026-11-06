package com.vexto.reflect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** A measured, accessible list item rendered in the library's design coordinates. */
final class ReflectLibraryRow extends View {
    private static final int MUTED = 0xff999999;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final float height;
    private final Drawing drawing;

    interface Drawing { void draw(Canvas canvas, TextPaint paint); }

    private ReflectLibraryRow(Context context, float height, String label, Drawing drawing) {
        super(context);
        this.height = height;
        this.drawing = drawing;
        setContentDescription(label);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    static ReflectLibraryRow heading(Context context, ReflectArtwork artwork, String title) {
        ReflectLibraryRow row = new ReflectLibraryRow(context, 47, title,
                (canvas, paint) -> text(canvas, paint, title, 16, 23, 24, Color.WHITE, artwork.display, -.24f));
        row.setAccessibilityHeading(true);
        return row;
    }

    static ReflectLibraryRow message(Context context, ReflectArtwork artwork, String message) {
        return new ReflectLibraryRow(context, 44, message,
                (canvas, paint) -> text(canvas, paint, message, 16, 25, 16, MUTED, artwork.regular, 0));
    }

    static ReflectLibraryRow recording(Context context, ReflectArtwork art, ReflectCatalog.Recording item, Runnable open) {
        boolean hasSpeakers = !item.portraits.isEmpty();
        ReflectLibraryRow row = new ReflectLibraryRow(context, hasSpeakers ? 109 : 88,
                item.title + ", " + item.time + ", " + item.length + ", " + item.status, (canvas, paint) -> {
            recordingCover(canvas, art, item.waveCover, 21);
            boolean arrow = !item.complete;
            String status = item.status;
            textRight(canvas, paint, status, arrow ? 361 : 377, 29, 17,
                    item.complete ? 0xff34c759 : MUTED, art.regular, -.43f);
            float available = (arrow ? 361 : 377) - 74 - 10 - paint.measureText(status);
            rowTitle(canvas, paint, art, item.title, available);
            text(canvas, paint, item.time + " · " + item.length, 74, 51, 16, MUTED, art.regular, -.23f);
            if (arrow) chevron(canvas, paint, 373, 23);
            if (hasSpeakers) {
                text(canvas, paint, "Участники:", 74, 83, 16, MUTED, art.regular, -.23f);
                for (int i = 0; i < item.portraits.size(); i++) {
                    art.image(canvas, item.portraits.get(i), 167 + 24 * i, 62, 32, 32);
                }
            }
            separator(canvas, paint, hasSpeakers ? 106 : 85);
        });
        row.setFocusable(true);
        row.setOnClickListener(view -> open.run());
        return row;
    }

    static ReflectLibraryRow speaker(Context context, ReflectArtwork art, ReflectCatalog.Speaker item) {
        return new ReflectLibraryRow(context, 84, item.name + ", " + item.duration, (canvas, paint) -> {
            art.image(canvas, item.avatar, 16, 0, 64, 64);
            Typeface nameFont = item.name.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp)
                    == Character.UnicodeScript.CYRILLIC) ? art.semibold : art.latinSemibold;
            text(canvas, paint, item.name, 92, 38.5f, 18, Color.WHITE, nameFont, -.306f);
            float badgeX = 104 + paint.measureText(item.name);
            style(paint, 14, 0xffae86e8, art.medium, -.42f);
            float badgeWidth = paint.measureText(item.duration) + 16;
            paint.setColor(0xff220053);
            canvas.drawRoundRect(badgeX, 19, badgeX + badgeWidth, 45, 10, 10, paint);
            text(canvas, paint, item.duration, badgeX + 8, 37, 14, 0xffae86e8, art.medium, -.42f);
        });
    }

    static ReflectLibraryRow conversation(Context context, ReflectArtwork art, ReflectCatalog.Conversation item,
                                          Runnable open) {
        ReflectLibraryRow row = new ReflectLibraryRow(context, 91,
                item.title + ", " + item.time + ", " + item.preview, (canvas, paint) -> {
            if (item.recording != null) recordingCover(canvas, art, item.recording.waveCover, 21);
            else conversationCover(canvas, art, item.roomCover);
            textRight(canvas, paint, item.time, 361, 29, 17, MUTED, art.duration, -.43f);
            float available = 279 - paint.measureText(item.time);
            rowTitle(canvas, paint, art, item.title, available);
            preview(canvas, paint, art, item.preview, available);
            chevron(canvas, paint, 373, 23);
            separator(canvas, paint, 88);
        });
        row.setFocusable(true);
        row.setOnClickListener(view -> open.run());
        return row;
    }

    private static void preview(Canvas canvas, Paint paint, ReflectArtwork art, String value, float width) {
        style(paint, 16, MUTED, art.regular, -.23f);
        int split = paint.breakText(value, true, width, null);
        if (split >= value.length()) {
            text(canvas, paint, value, 74, 51, 16, MUTED, art.regular, -.23f);
            return;
        }
        int lastSpace = value.lastIndexOf(' ', split);
        if (lastSpace > 0) split = lastSpace;
        text(canvas, paint, value.substring(0, split), 74, 51, 16, MUTED, art.regular, -.23f);
        String rest = value.substring(split).trim();
        if (paint.measureText(rest) > width) {
            int chars = paint.breakText(rest, true, width - paint.measureText("…"), null);
            rest = rest.substring(0, chars).trim() + "…";
        }
        text(canvas, paint, rest, 74, 71, 16, MUTED, art.regular, -.23f);
    }

    private static void rowTitle(Canvas canvas, TextPaint paint, ReflectArtwork art, String title, float width) {
        style(paint, 18, Color.WHITE, art.semibold, -.43f);
        paint.setTextAlign(Paint.Align.LEFT);
        CharSequence fitted = TextUtils.ellipsize(title, paint, Math.max(0, width), TextUtils.TruncateAt.END);
        canvas.drawText(fitted, 0, fitted.length(), 74, 29, paint);
    }

    private static void conversationCover(Canvas canvas, ReflectArtwork art, boolean room) {
        canvas.save();
        Path clip = new Path();
        clip.addCircle(39, 44, 23, Path.Direction.CW);
        canvas.clipPath(clip);
        if (room) {
            art.image(canvas, "conversation_room", -29, -203, 261, 564);
        } else {
            canvas.scale(-1, 1, 39.5f, 43.5f);
            art.image(canvas, "conversation_sprint", 14, 11, 51, 65);
        }
        canvas.restore();
    }

    private static void recordingCover(Canvas canvas, ReflectArtwork art, boolean wave, float y) {
        canvas.save();
        Path clip = new Path();
        clip.addCircle(39, y + 23, 23, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.save();
        canvas.scale(1, -1, 39.5f, y + 23);
        art.image(canvas, "record_prism", 9, y - 38, 61, 122);
        canvas.restore();
        if (wave) art.image(canvas, "record_wave", 9, y - 22, 60, 90);
        canvas.restore();
    }

    static void separator(Canvas canvas, Paint paint, float y) {
        paint.setColor(0xff0f0f0e);
        canvas.drawRect(74, y, 393, y + 3, paint);
    }

    static void chevron(Canvas canvas, Paint paint, float x, float y) {
        paint.setColor(0x4dffffff);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x, y - 5, x + 4.5f, y, paint);
        canvas.drawLine(x + 4.5f, y, x, y + 5, paint);
    }

    static void text(Canvas canvas, Paint paint, String value, float x, float baseline, float size,
                     int color, Typeface typeface, float tracking) {
        style(paint, size, color, typeface, tracking);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, x, baseline, paint);
    }

    static void textRight(Canvas canvas, Paint paint, String value, float x, float baseline, float size,
                          int color, Typeface typeface, float tracking) {
        style(paint, size, color, typeface, tracking);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(value, x, baseline, paint);
    }

    static void style(Paint paint, float size, int color, Typeface typeface, float tracking) {
        paint.setColor(color);
        paint.setTypeface(typeface);
        paint.setTextSize(size);
        paint.setLetterSpacing(tracking / size);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (isClickable()) info.setClassName("android.widget.Button");
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(height * width / 393f));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        float scale = getWidth() / 393f;
        canvas.scale(scale, scale);
        drawing.draw(canvas, paint);
        canvas.restore();
    }
}
