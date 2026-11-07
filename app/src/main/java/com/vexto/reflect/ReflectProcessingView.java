package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import com.vexto.engine.model.Stage;
import java.util.ArrayList;
import java.util.List;

/** Figma processing stage, driven by the same local job that saves the transcript. */
final class ReflectProcessingView extends ViewGroup {
    private static final int GREEN = 0xff34c759, TRACK = 0xff2f2f2d, MUTED = 0xff999999;
    private static final int ROW_TOP = 270, ROW_SPACING = 75;
    private static final float ORB_SCALE = 40f / 238f;
    private static final String[] STEPS = {
            "Подготовка аудио", "Спикеры и язык", "Распознавание речи", "Сравнение и имена", "Текст и эмоции"
    };

    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final TextView backgroundAction;
    private final TextView percentage;
    private final TextView[] subtitles = new TextView[STEPS.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<TextSlot> slots = new ArrayList<>();
    private Stage stage = Stage.Preparing;
    private int progress;
    private float displayedProgress;
    private float stageArrival = 1;
    private ValueAnimator progressAnimator;
    private float scale;

    ReflectProcessingView(Context context, ReflectArtwork artwork, ReflectPreferences preferences,
                          Runnable goToBackground) {
        super(context);
        this.artwork = artwork;
        this.preferences = preferences;
        setWillNotDraw(false);
        backgroundAction = new TextView(context) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.Button");
            }
        };
        backgroundAction.setText("Завершить в фоне");
        backgroundAction.setTextColor(Color.WHITE);
        backgroundAction.setIncludeFontPadding(false);
        backgroundAction.setTypeface(artwork.medium);
        backgroundAction.setGravity(Gravity.CENTER);
        backgroundAction.setFocusable(true);
        backgroundAction.setOnClickListener(view -> goToBackground.run());
        addView(backgroundAction);

        TextView title = addText("Расшифровываю", 0, 170, 393, 22, artwork.display, Color.WHITE, Gravity.CENTER);
        title.setAccessibilityHeading(true);
        addText("запись локально", 0, 203, 393, 22, artwork.display, Color.WHITE, Gravity.CENTER);
        for (int i = 0; i < STEPS.length; i++) {
            int top = ROW_TOP + ROW_SPACING * i;
            TextView label = addText(STEPS[i], 118, top + 17, 257,
                    18, artwork.semibold, Color.WHITE, Gravity.START);
            label.getPaint().setFakeBoldText(true);
            subtitles[i] = addText("", 118, top + 36, 257, 13, artwork.medium, MUTED, Gravity.START);
        }
        addText("Уже заполнено", 45, 759, 204, 20, artwork.semibold, Color.WHITE, Gravity.START);
        percentage = addText("", 249, 760, 99, 24, artwork.display, Color.WHITE, Gravity.END);
        content("0:00", null);
    }

    void content(String duration, ReflectLocalTranscription.Progress value) {
        Stage previousStage = stage;
        stage = value == null ? Stage.Preparing : value.stage();
        int stagePercent = value == null ? 0 : value.percent();
        int speakers = value == null ? 0 : value.speakers();
        animateProgress(value == null ? 0 : value.overallPercent(), stage != previousStage);
        for (int index = 0; index < STEPS.length; index++) {
            String subtitle = switch (state(index)) {
                case WAITING -> "Ожидает обработки";
                case ACTIVE -> (value == null ? "Готовлю обработку" : value.title())
                        + (stage == null ? "" : " · " + stagePercent + "%");
                case COMPLETE -> switch (index) {
                    case 1 -> speakers == 0 ? "Готово" : "Найдено " + speakers + " " + voiceWord(speakers);
                    case 2 -> "Готово · " + duration + " из " + duration;
                    default -> "Готово";
                };
            };
            update(subtitles[index], subtitle);
        }
        invalidate();
    }

    private void animateProgress(int target, boolean stageChanged) {
        if (progress == target && !stageChanged) {
            update(percentage, target + "%");
            return;
        }
        progress = target;
        if (progressAnimator != null) progressAnimator.cancel();
        if (!isAttachedToWindow() || !preferences.motionEnabled() || displayedProgress > target) {
            displayedProgress = target;
            stageArrival = 1;
        } else {
            float start = displayedProgress;
            float startArrival = stageChanged ? 0 : stageArrival;
            progressAnimator = ValueAnimator.ofFloat(0, 1);
            progressAnimator.setDuration(stageChanged ? 380 : 220);
            progressAnimator.setInterpolator(ReflectMotion.EASE);
            progressAnimator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                displayedProgress = start + (target - start) * fraction;
                stageArrival = startArrival + (1 - startArrival) * fraction;
                invalidate();
            });
            progressAnimator.start();
        }
        update(percentage, target + "%");
    }

    @Override protected void onDetachedFromWindow() {
        if (progressAnimator != null) progressAnimator.cancel();
        displayedProgress = progress;
        stageArrival = 1;
        super.onDetachedFromWindow();
    }

    private static void update(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private TextView addText(String text, int left, int baseline, int width, int size,
                             Typeface font, int color, int gravity) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(color);
        view.setTypeface(font);
        view.setIncludeFontPadding(false);
        view.setSingleLine();
        view.setGravity(gravity);
        slots.add(new TextSlot(view, left, baseline, width, size));
        addView(view);
        return view;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float nextScale = width / 393f;
        if (scale != nextScale) {
            scale = nextScale;
            backgroundAction.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * scale);
            ReflectPageStyle style = new ReflectPageStyle(getContext(), artwork, width);
            setBackground(style.rounded(0xff040402, 47));
            backgroundAction.setBackground(style.pressable(ReflectPageStyle.ACCENT, 24));
            for (TextSlot slot : slots) slot.view().setTextSize(TypedValue.COMPLEX_UNIT_PX, slot.size() * scale);
        }
        backgroundAction.measure(exact(198), exact(48));
        for (TextSlot slot : slots) {
            slot.view().measure(exact(slot.width()), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        setMeasuredDimension(width, MeasureSpec.getSize(heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        place(backgroundAction, px(96), px(75));
        for (TextSlot slot : slots) place(slot.view(), px(slot.left()), px(slot.baseline()) - slot.view().getBaseline());
    }

    private int px(int value) { return Math.round(value * scale); }
    private int exact(int value) { return MeasureSpec.makeMeasureSpec(px(value), MeasureSpec.EXACTLY); }
    private static void place(View view, int left, int top) {
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scale, scale);
        for (int i = 0; i < STEPS.length; i++) {
            float y = ROW_TOP + ROW_SPACING * i;
            StepState state = state(i);
            if (state == StepState.ACTIVE) {
                // The source's visible disk starts at (24, 7) within the transparent 263 × 252 PNG.
                canvas.save();
                float orbSize = .86f + .14f * stageArrival;
                canvas.scale(orbSize, orbSize, 86, y + 20);
                if (stageArrival < 1) {
                    paint.setColor(Color.argb(Math.round(85 * (1 - stageArrival)), 115, 153, 255));
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1);
                    canvas.drawCircle(86, y + 20, 21 + 7 * stageArrival, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                artwork.image(canvas, "processing_orb", 66 - 24 * ORB_SCALE, y - 7 * ORB_SCALE,
                        263 * ORB_SCALE, 252 * ORB_SCALE);
                canvas.restore();
            } else {
                paint.setColor(state == StepState.COMPLETE ? GREEN : TRACK);
                canvas.drawCircle(86, y + 20, 20, paint);
                if (state == StepState.COMPLETE) artwork.centeredIcon(canvas, "processing_check", 86, y + 20);
            }
        }
        paint.setColor(TRACK);
        canvas.drawRoundRect(45, 784, 348, 804, 10, 10, paint);
        if (displayedProgress > 0) {
            paint.setColor(GREEN);
            canvas.drawRoundRect(45, 784, 45 + 303 * displayedProgress / 100f, 804, 10, 10, paint);
        }
        canvas.restore();
    }

    private StepState state(int step) {
        int activeStep = ReflectPipelineProgress.step(stage);
        if (step < activeStep) return StepState.COMPLETE;
        return step == activeStep ? StepState.ACTIVE : StepState.WAITING;
    }

    private static String voiceWord(int speakers) {
        if (speakers % 100 >= 11 && speakers % 100 <= 14) return "голосов";
        return switch (speakers % 10) {
            case 1 -> "голос";
            case 2, 3, 4 -> "голоса";
            default -> "голосов";
        };
    }

    private enum StepState { WAITING, ACTIVE, COMPLETE }
    private record TextSlot(TextView view, int left, int baseline, int width, int size) {}
}
