package com.vexto.reflect;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Inline summary card that scrolls with the transcript. */
final class ReflectSummaryPanel extends LinearLayout {
    private final TextView status;
    private final TextView text;
    private final ProgressBar progress;
    private final TextView retry;

    ReflectSummaryPanel(Context context, ReflectPageStyle style, Runnable close, Runnable retrySummary) {
        super(context);
        setOrientation(VERTICAL);
        setBackground(style.rounded(ReflectPageStyle.CARD, 24));
        setPadding(style.px(16), style.px(8), style.px(12), style.px(16));

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = style.text("ИИ-резюме", 17, Color.WHITE, style.art.semibold);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LayoutParams(0, WRAP_CONTENT, 1));
        View closeButton = new View(context) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                int saved = canvas.save();
                canvas.translate(getWidth() / 2f, getHeight() / 2f);
                float scale = getWidth() / 60f;
                canvas.scale(scale, scale);
                canvas.rotate(45f);
                style.art.centeredIcon(canvas, "plus", 0, 0);
                canvas.restoreToCount(saved);
            }

            @Override public CharSequence getAccessibilityClassName() {
                return Button.class.getName();
            }
        };
        closeButton.setContentDescription("Закрыть ИИ-резюме");
        closeButton.setFocusable(true);
        closeButton.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x30ffffff), null, style.rounded(Color.WHITE, 22)));
        closeButton.setOnClickListener(v -> close.run());
        heading.addView(closeButton, new LayoutParams(style.px(44), style.px(44)));
        addView(heading, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        status = style.text("", 15, ReflectPageStyle.MUTED, style.art.regular);
        style.append(this, status, 0, 8);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ReflectPageStyle.ACCENT));
        progress.setContentDescription("Прогресс подготовки ИИ-резюме");
        style.append(this, progress, 0, 4);

        text = style.text("", 15, Color.WHITE, style.art.regular);
        text.setGravity(Gravity.START | Gravity.TOP);
        text.setLineHeight(style.px(22));
        text.setTextIsSelectable(true);
        style.append(this, text, 0, 0);

        retry = style.text("Повторить", 16, Color.WHITE, style.art.medium);
        retry.setGravity(Gravity.CENTER);
        retry.setMinHeight(style.px(44));
        retry.setFocusable(true);
        retry.setBackground(style.pressable(0xff353535, 24));
        retry.setOnClickListener(v -> retrySummary.run());
        style.append(this, retry, 4, 0);
    }

    void show(ReflectSummaryState state) {
        if (state == null) {
            setVisibility(GONE);
            return;
        }
        String detail = state.status();
        if (state.running() && state.percent() >= 0) {
            detail = detail == null || detail.trim().isEmpty() ? state.percent() + "%" : detail + " · " + state.percent() + "%";
        }
        updateText(status, detail);
        progress.setVisibility(state.running() ? VISIBLE : GONE);
        if (state.running()) {
            boolean waiting = state.percent() < 0;
            if (progress.isIndeterminate() != waiting) progress.setIndeterminate(waiting);
            if (!waiting) progress.setProgress(state.percent());
        }
        updateText(text, state.text());
        retry.setVisibility(state.retryable() ? VISIBLE : GONE);
        setVisibility(VISIBLE);
    }

    private static void updateText(TextView view, String value) {
        boolean visible = value != null && !value.trim().isEmpty();
        view.setVisibility(visible ? VISIBLE : GONE);
        if (visible && !value.contentEquals(view.getText())) view.setText(value);
    }
}
