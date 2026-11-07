package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import java.util.function.Consumer;

/** Audio controls that stay visible while the transcript scrolls. */
final class ReflectConversationPlayer extends FrameLayout {
    private static final float WIDTH = 361f;
    private static final float WAVE_START = 47f;
    private static final float WAVE_END = 290f;
    private static final int SEEK_STEP_MS = 5_000;
    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path playGlyph = new Path();
    private final String length;
    private final String audioLabel;
    private final View playButton;
    private final Scrubber scrubber;
    private int[] bars;
    private int positionMs, durationMs;
    private float preview = -1f;
    private boolean playing;
    private ValueAnimator glyphAnimation;
    private float pauseGlyph;
    private String time;
    private int displayedSeconds = -1;

    ReflectConversationPlayer(Context context, ReflectArtwork artwork, ReflectPreferences preferences,
                              String length, String audioLabel, Runnable toggle, Consumer<Float> seek) {
        super(context);
        setWillNotDraw(false);
        this.artwork = artwork;
        this.preferences = preferences;
        this.length = length;
        this.audioLabel = audioLabel;
        time = "+ " + length;
        playGlyph.moveTo(18, 17);
        playGlyph.lineTo(27, 22);
        playGlyph.lineTo(18, 27);
        playGlyph.close();
        playButton = new View(context) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.Button");
            }
        };
        playButton.setContentDescription(playLabel(false));
        playButton.setFocusable(true);
        playButton.setOnClickListener(view -> toggle.run());
        addView(playButton);
        scrubber = new Scrubber(context, seek);
        addView(scrubber);
    }

    void update(boolean playing, int position, int duration) {
        if (this.playing != playing) {
            playButton.setContentDescription(playLabel(playing));
            animateGlyph(playing ? 1f : 0f);
        }
        this.playing = playing;
        positionMs = Math.min(Math.max(0, duration), Math.max(0, position));
        if (durationMs != duration) {
            durationMs = duration;
            scrubber.setEnabled(duration > 0);
        }
        updateTime();
        invalidate();
    }

    private String playLabel(boolean playing) {
        return (playing ? "Приостановить " : "Воспроизвести ") + audioLabel + ", " + length;
    }

    private void updateTime() {
        int seconds = preview >= 0 ? (int) (preview * durationMs / 1000)
                : positionMs == 0 ? -1 : positionMs / 1000;
        if (displayedSeconds != seconds) {
            displayedSeconds = seconds;
            int remainder = seconds % 60;
            time = seconds < 0 ? "+ " + length : seconds / 60 + ":" + (remainder < 10 ? "0" : "") + remainder;
        }
    }

    void waveform(int[] bars) {
        this.bars = bars;
        invalidate();
    }

    private void animateGlyph(float target) {
        if (glyphAnimation != null) glyphAnimation.cancel();
        if (!isAttachedToWindow() || !preferences.motionEnabled()) {
            pauseGlyph = target;
            return;
        }
        glyphAnimation = ValueAnimator.ofFloat(pauseGlyph, target);
        glyphAnimation.setDuration(145);
        glyphAnimation.setInterpolator(ReflectMotion.EASE);
        glyphAnimation.addUpdateListener(animation -> {
            pauseGlyph = (float) animation.getAnimatedValue();
            invalidate();
        });
        glyphAnimation.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (glyphAnimation != null) {
            glyphAnimation.cancel();
            glyphAnimation = null;
        }
        pauseGlyph = playing ? 1f : 0f;
        preview = -1f;
        updateTime();
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int playWidth = Math.round(width * 43 / WIDTH);
        int seekRight = Math.round(width * 297 / WIDTH);
        int exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        playButton.measure(MeasureSpec.makeMeasureSpec(playWidth, MeasureSpec.EXACTLY), exactHeight);
        scrubber.measure(MeasureSpec.makeMeasureSpec(seekRight - playWidth, MeasureSpec.EXACTLY), exactHeight);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int playWidth = playButton.getMeasuredWidth();
        playButton.layout(0, 0, playWidth, getHeight());
        scrubber.layout(playWidth, 0, playWidth + scrubber.getMeasuredWidth(), getHeight());
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / WIDTH, getHeight() / 44f);
        float progress = preview >= 0 ? preview : fractionAt(positionMs);
        pill(canvas, 0, 0, 361, 44, 22, 0xff090908);
        paint.setColor(0xff555554);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.8f);
        canvas.drawRoundRect(.5f, .5f, 360.5f, 43.5f, 22, 22, paint);
        paint.setStyle(Paint.Style.FILL);
        pill(canvas, 7, 7, 37, 37, 15, 0xff181818);
        if (pauseGlyph < 1f) {
            paint.setColor(white(1f - pauseGlyph));
            canvas.drawPath(playGlyph, paint);
        }
        if (pauseGlyph > 0f) {
            int color = white(pauseGlyph);
            pill(canvas, 18, 17, 20.5f, 27, 1, color);
            pill(canvas, 23.5f, 17, 26, 27, 1, color);
        }

        if (bars != null && bars.length > 1) {
            for (int index = 0; index < bars.length; index++) {
                float fraction = index / (float) (bars.length - 1);
                float x = WAVE_START + (WAVE_END - WAVE_START - 1) * fraction;
                float height = Math.max(2, Math.min(22, bars[index]));
                pill(canvas, x, 22 - height / 2, x + 2, 22 + height / 2, 1,
                        fraction <= progress && progress > 0 ? Color.WHITE : 0xff999999);
            }
        } else {
            pill(canvas, WAVE_START, 21, WAVE_END, 23, 1, 0xff4a4a49);
            if (progress > 0) {
                pill(canvas, WAVE_START, 21, WAVE_START + (WAVE_END - WAVE_START) * progress, 23, 1, Color.WHITE);
            }
        }
        if (preview >= 0) {
            float marker = WAVE_START + (WAVE_END - WAVE_START) * preview;
            pill(canvas, marker - 1f, 9, marker + 1f, 35, 1, Color.WHITE);
        }

        pill(canvas, 300, 10, 354, 34, 12, 0xff181818);
        paint.setColor(Color.WHITE);
        paint.setTypeface(artwork.duration);
        paint.setTextSize(13);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(time, 327, 26.6f, paint);
        canvas.restore();
    }

    private static int white(float opacity) {
        return Color.argb(Math.round(opacity * 255), 255, 255, 255);
    }

    private void pill(Canvas canvas, float left, float top, float right, float bottom, float radius, int color) {
        paint.setColor(color);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    }

    private float fractionAt(float position) {
        return Math.min(1f, Math.max(0, position) / Math.max(1, durationMs));
    }

    private final class Scrubber extends View {
        private final Consumer<Float> seek;
        private int pointer = -1;

        Scrubber(Context context, Consumer<Float> seek) {
            super(context);
            this.seek = seek;
            setContentDescription("Позиция воспроизведения");
            setFocusable(true);
            setEnabled(false);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pointer = event.getPointerId(0);
                getParent().requestDisallowInterceptTouchEvent(true);
                preview(event.getX());
                return true;
            }
            int index = event.findPointerIndex(pointer);
            if (action == MotionEvent.ACTION_CANCEL || index < 0) {
                finish(false);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) preview(event.getX(index));
            if (action == MotionEvent.ACTION_UP
                    || (action == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == index)) {
                preview(event.getX(index));
                finish(true);
            }
            return true;
        }

        private void preview(float localX) {
            float x = (getLeft() + localX) * WIDTH / ReflectConversationPlayer.this.getWidth();
            preview = Math.min(1f, Math.max(0f, (x - WAVE_START) / (WAVE_END - WAVE_START)));
            updateTime();
            ReflectConversationPlayer.this.invalidate();
        }

        private void finish(boolean commit) {
            if (commit) performClick();
            pointer = -1;
            preview = -1f;
            getParent().requestDisallowInterceptTouchEvent(false);
            updateTime();
            ReflectConversationPlayer.this.invalidate();
        }

        @Override public boolean performClick() {
            super.performClick();
            if (preview < 0) return false;
            seek.accept(preview);
            return true;
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (isEnabled() && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                step(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.SeekBar");
            if (!isEnabled()) return;
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                    0f, durationMs / 1000f, positionMs / 1000f));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            if (positionMs > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (positionMs < durationMs) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (!isEnabled()) return super.performAccessibilityAction(action, arguments);
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null) {
                float seconds = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN);
                if (!Float.isFinite(seconds)) return false;
                seek.accept(fractionAt(seconds * 1000f));
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                step(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }

        private void step(boolean forward) {
            seek.accept(fractionAt(positionMs + (forward ? SEEK_STEP_MS : -SEEK_STEP_MS)));
        }

        @Override protected void onDetachedFromWindow() {
            pointer = -1;
            super.onDetachedFromWindow();
        }
    }
}
