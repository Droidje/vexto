package com.vexto.reflect;

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
    private static final int ROW_TOP = 297, ROW_SPACING = 72;
    private static final float ORB_SCALE = 40f / 238f;
    private static final Step[] STEPS = {
            new Step("Предобработка аудио", Stage.Preparing, Stage.Decoding),
            new Step("Распознавание речи", Stage.Recognition, Stage.Recognition),
            new Step("Определение спикеров", Stage.Diarization, Stage.Diarization),
            new Step("Умная постобработка", Stage.Assembly, Stage.Emotion)
    };

    private final ReflectArtwork artwork;
    private final TextView backgroundAction;
    private final TextView percentage;
    private final TextView[] subtitles = new TextView[STEPS.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<TextSlot> slots = new ArrayList<>();
    private Stage stage = Stage.Preparing;
    private int progress;
    private float scale;

    ReflectProcessingView(Context context, ReflectArtwork artwork, Runnable goToBackground) {
        super(context);
        this.artwork = artwork;
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
            TextView label = addText(STEPS[i].title(), 118, top + (i == 3 ? 18 : 17), 257,
                    18, artwork.semibold, Color.WHITE, Gravity.START);
            label.getPaint().setFakeBoldText(true);
            subtitles[i] = addText("", 118, top + 36, 257, 13, artwork.medium, MUTED, Gravity.START);
        }
        addText("Уже заполнено", 45, 759, 204, 20, artwork.semibold, Color.WHITE, Gravity.START);
        percentage = addText("", 249, 760, 99, 24, artwork.display, Color.WHITE, Gravity.END);
        content("0:00", null);
    }

    void content(String duration, ReflectLocalTranscription.Progress value) {
        stage = value == null ? Stage.Preparing : value.stage();
        int stagePercent = value == null ? 0 : value.percent();
        int speakers = value == null ? 0 : value.speakers();
        progress = totalProgress(stage, stagePercent);
        update(percentage, progress + "%");
        update(subtitles[0], "нормализация громкости");
        update(subtitles[1], switch (state(STEPS[1])) {
            case WAITING -> "Ожидает распознавания";
            case ACTIVE -> "Распознаю речь · " + stagePercent + "%";
            case COMPLETE -> "Готово · " + duration + " из " + duration;
        });
        update(subtitles[2], switch (state(STEPS[2])) {
            case WAITING -> "Разделение голосов";
            case ACTIVE -> "Определяю голоса…";
            case COMPLETE -> speakers == 0 ? "Готово" : "Найдено " + speakers + " " + voiceWord(speakers);
        });
        update(subtitles[3], stage == null ? "Сохраняю расшифровку" : "Структура текста и эмоции");
        invalidate();
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
            StepState state = state(STEPS[i]);
            if (state == StepState.ACTIVE) {
                // The source's visible disk starts at (24, 7) within the transparent 263 × 252 PNG.
                artwork.image(canvas, "processing_orb", 66 - 24 * ORB_SCALE, y - 7 * ORB_SCALE,
                        263 * ORB_SCALE, 252 * ORB_SCALE);
            } else {
                paint.setColor(state == StepState.COMPLETE ? GREEN : TRACK);
                canvas.drawCircle(86, y + 20, 20, paint);
                if (state == StepState.COMPLETE) artwork.centeredIcon(canvas, "processing_check", 86, y + 20);
            }
        }
        paint.setColor(TRACK);
        canvas.drawRoundRect(45, 784, 348, 804, 10, 10, paint);
        if (progress > 0) {
            paint.setColor(GREEN);
            canvas.drawRoundRect(45, 784, 45 + 303 * progress / 100f, 804, 10, 10, paint);
        }
        canvas.restore();
    }

    private StepState state(Step step) {
        if (stage == null) return step.last() == Stage.Emotion ? StepState.ACTIVE : StepState.COMPLETE;
        if (stage.compareTo(step.first()) < 0) return StepState.WAITING;
        return stage.compareTo(step.last()) > 0 ? StepState.COMPLETE : StepState.ACTIVE;
    }

    // Phase weights give a single monotonic bar from the real per-phase progress, not a time estimate.
    private static int totalProgress(Stage stage, int percent) {
        if (stage == null) return 100;
        return switch (stage) {
            case Preparing -> part(0, 5, percent);
            case Decoding -> part(5, 15, percent);
            case Diarization -> part(15, 40, percent);
            case Recognition -> part(40, 75, percent);
            case Assembly -> part(75, 78, percent);
            case Punctuation -> part(78, 80, percent);
            case Emotion -> part(80, 99, percent);
        };
    }

    private static int part(int start, int end, int percent) {
        return start + Math.round((end - start) * percent / 100f);
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
    private record Step(String title, Stage first, Stage last) {}
    private record TextSlot(TextView view, int left, int baseline, int width, int size) {}
}
