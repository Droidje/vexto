package com.vexto.reflect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Audio controls that stay visible while the transcript scrolls. */
final class ReflectConversationPlayer extends View {
    private final ReflectArtwork artwork;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path playGlyph = new Path();
    private final String length;
    private int[] bars;
    private float progress;
    private boolean playing;
    private String time;
    private int displayedSeconds = -1;

    ReflectConversationPlayer(Context context, ReflectArtwork artwork, String length, Runnable toggle) {
        super(context);
        this.artwork = artwork;
        this.length = length;
        time = "+ " + length;
        playGlyph.moveTo(18, 17);
        playGlyph.lineTo(27, 22);
        playGlyph.lineTo(18, 27);
        playGlyph.close();
        setContentDescription("Воспроизвести разговор, " + length);
        setFocusable(true);
        setOnClickListener(view -> toggle.run());
    }

    void update(boolean playing, int position, int duration) {
        if (this.playing != playing) {
            setContentDescription((playing ? "Приостановить разговор, " : "Воспроизвести разговор, ") + length);
        }
        this.playing = playing;
        progress = Math.min(1f, Math.max(0, position) / (float) Math.max(1, duration));
        int seconds = position == 0 ? -1 : position / 1000;
        if (displayedSeconds != seconds) {
            displayedSeconds = seconds;
            int remainder = seconds % 60;
            time = seconds < 0 ? "+ " + length : seconds / 60 + ":" + (remainder < 10 ? "0" : "") + remainder;
        }
        invalidate();
    }

    void waveform(int[] bars) {
        this.bars = bars;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / 361f, getHeight() / 44f);
        pill(canvas, 0, 0, 361, 44, 22, 0xff090908);
        paint.setColor(0xff555554);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.8f);
        canvas.drawRoundRect(.5f, .5f, 360.5f, 43.5f, 22, 22, paint);
        paint.setStyle(Paint.Style.FILL);
        pill(canvas, 7, 7, 37, 37, 15, 0xff181818);
        if (playing) {
            pill(canvas, 18, 17, 20.5f, 27, 1, Color.WHITE);
            pill(canvas, 23.5f, 17, 26, 27, 1, Color.WHITE);
        } else {
            paint.setColor(Color.WHITE);
            canvas.drawPath(playGlyph, paint);
        }

        if (bars != null && bars.length > 1) {
            for (int index = 0; index < bars.length; index++) {
                float fraction = index / (float) (bars.length - 1);
                float x = 47 + 242 * fraction;
                float height = Math.max(2, Math.min(22, bars[index]));
                pill(canvas, x, 22 - height / 2, x + 2, 22 + height / 2, 1,
                        fraction <= progress && progress > 0 ? Color.WHITE : 0xff999999);
            }
        } else {
            pill(canvas, 47, 21, 290, 23, 1, 0xff4a4a49);
            if (progress > 0) pill(canvas, 47, 21, 47 + 243 * progress, 23, 1, Color.WHITE);
        }

        pill(canvas, 300, 10, 354, 34, 12, 0xff181818);
        paint.setColor(Color.WHITE);
        paint.setTypeface(artwork.duration);
        paint.setTextSize(13);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(time, 327, 26.6f, paint);
        canvas.restore();
    }

    private void pill(Canvas canvas, float left, float top, float right, float bottom, float radius, int color) {
        paint.setColor(color);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
    }
}
