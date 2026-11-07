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
    private static final Path COVER_CLIP = coverClip();
    private float height;
    private Drawing drawing;

    private interface Drawing { void draw(Canvas canvas, TextPaint paint); }

    private ReflectLibraryRow(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private ReflectLibraryRow bind(float height, String label, boolean heading, Runnable open, Drawing drawing) {
        if (this.height != height) {
            this.height = height;
            requestLayout();
        }
        this.drawing = drawing;
        setContentDescription(label);
        setAccessibilityHeading(heading);
        setOnClickListener(open == null ? null : view -> open.run());
        setClickable(open != null);
        setFocusable(open != null);
        invalidate();
        return this;
    }

    static ReflectLibraryRow reuse(Context context, View recycled) {
        return recycled instanceof ReflectLibraryRow ? (ReflectLibraryRow) recycled : new ReflectLibraryRow(context);
    }

    ReflectLibraryRow heading(ReflectArtwork artwork, String title) {
        return bind(47, title, true, null,
                (canvas, paint) -> text(canvas, paint, title, 16, 23, 24, Color.WHITE, artwork.display, -.24f));
    }

    ReflectLibraryRow message(ReflectArtwork artwork, String message) {
        return bind(44, message, false, null,
                (canvas, paint) -> text(canvas, paint, message, 16, 25, 16, MUTED, artwork.regular, 0));
    }

    ReflectLibraryRow recording(ReflectArtwork art, ReflectCatalog.Recording item, Runnable open) {
        boolean hasSpeakers = !item.portraits.isEmpty();
        String timeAndLength = item.time + " · " + item.length;
        return bind(hasSpeakers ? 109 : 88,
                item.title + ", " + item.time + ", " + item.length + ", " + item.status, false, open, (canvas, paint) -> {
            recordingCover(canvas, art, item.waveCover);
            boolean arrow = !item.complete;
            String status = item.status;
            textRight(canvas, paint, status, arrow ? 361 : 377, 29, 17,
                    item.complete ? 0xff34c759 : MUTED, art.regular, -.43f);
            float available = (arrow ? 361 : 377) - 74 - 10 - paint.measureText(status);
            rowTitle(canvas, paint, art, item.title, available);
            text(canvas, paint, timeAndLength, 74, 51, 16, MUTED, art.regular, -.23f);
            if (arrow) chevron(canvas, paint, 373, 23);
            if (hasSpeakers) {
                text(canvas, paint, "Участники:", 74, 83, 16, MUTED, art.regular, -.23f);
                for (int i = 0; i < item.portraits.size(); i++) {
                    art.image(canvas, item.portraits.get(i), 167 + 24 * i, 62, 32, 32);
                }
            }
            separator(canvas, paint, hasSpeakers ? 106 : 85);
        });
    }

    ReflectLibraryRow speaker(ReflectArtwork art, ReflectCatalog.Speaker item, Runnable open) {
        Typeface nameFont = item.name.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp)
                == Character.UnicodeScript.CYRILLIC) ? art.semibold : art.latinSemibold;
        return bind(84, item.duration.isEmpty() ? item.name : item.name + ", " + item.duration,
                false, open, (canvas, paint) -> {
            if (item.avatar == null) {
                paint.setColor(0xff2c1740);
                canvas.drawCircle(48, 32, 32, paint);
                String initial = item.name.substring(0, item.name.offsetByCodePoints(0, 1));
                style(paint, 25, 0xffd4baff, art.display, 0);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(initial, 48, 41, paint);
            } else art.image(canvas, item.avatar, 16, 0, 64, 64);
            if (item.duration.isEmpty()) {
                style(paint, 18, Color.WHITE, nameFont, -.306f);
                CharSequence name = TextUtils.ellipsize(item.name, paint, 257, TextUtils.TruncateAt.END);
                text(canvas, paint, name.toString(), 92, 38.5f, 18, Color.WHITE, nameFont, -.306f);
                if (open != null) chevron(canvas, paint, 373, 33);
                return;
            }
            style(paint, 14, 0xffae86e8, art.durationBadge, -.42f);
            float badgeWidth = paint.measureText(item.duration) + 16;
            style(paint, 18, Color.WHITE, nameFont, -.306f);
            float nameWidth = (open == null ? 377 : 354) - 104 - badgeWidth;
            String name = TextUtils.ellipsize(item.name, paint, Math.max(0, nameWidth), TextUtils.TruncateAt.END).toString();
            float badgeX = 104 + paint.measureText(name);
            text(canvas, paint, name, 92, 38.5f, 18, Color.WHITE, nameFont, -.306f);
            paint.setColor(0xff220053);
            canvas.drawRoundRect(badgeX, 19, badgeX + badgeWidth, 45, 10, 10, paint);
            text(canvas, paint, item.duration, badgeX + 8, 37, 14, 0xffae86e8, art.durationBadge, -.42f);
            if (open != null) chevron(canvas, paint, 373, 33);
        });
    }

    ReflectLibraryRow conversation(ReflectArtwork art, ReflectCatalog.Conversation item, Runnable open) {
        return bind(91,
                item.title + ", " + item.time + ", " + item.preview, false, open, (canvas, paint) -> {
            if (item.recording != null) recordingCover(canvas, art, item.recording.waveCover);
            else conversationCover(canvas, art, item.roomCover);
            textRight(canvas, paint, item.time, 361, 29, 17, MUTED, art.duration, -.43f);
            float available = 279 - paint.measureText(item.time);
            rowTitle(canvas, paint, art, item.title, available);
            preview(canvas, paint, art, item.preview, available);
            chevron(canvas, paint, 373, 23);
            separator(canvas, paint, 88);
        });
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
        canvas.clipPath(COVER_CLIP);
        if (room) {
            art.image(canvas, "conversation_room", -29, -203, 261, 564);
        } else {
            canvas.scale(-1, 1, 39.5f, 43.5f);
            art.image(canvas, "conversation_sprint", 14, 11, 51, 65);
        }
        canvas.restore();
    }

    private static void recordingCover(Canvas canvas, ReflectArtwork art, boolean wave) {
        canvas.save();
        canvas.clipPath(COVER_CLIP);
        canvas.save();
        canvas.scale(1, -1, 39.5f, 44);
        art.image(canvas, "record_prism", 9, -17, 61, 122);
        canvas.restore();
        if (wave) art.image(canvas, "record_wave", 9, -1, 60, 90);
        canvas.restore();
    }

    private static Path coverClip() {
        Path clip = new Path();
        clip.addCircle(39, 44, 23, Path.Direction.CW);
        return clip;
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
