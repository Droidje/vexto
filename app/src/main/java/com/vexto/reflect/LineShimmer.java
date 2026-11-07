package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Синее переливание текста, пока фрагмент заново проходит пайплайн. */
final class LineShimmer {
    private static final WeakHashMap<TextView, Wave> ACTIVE = new WeakHashMap<>();

    private LineShimmer() {}

    static void start(TextView view, boolean motion) {
        stop(view);
        String plain = view.getText() == null ? "" : view.getText().toString();
        view.setTextColor(LineShimmerColor.REST);
        if (!motion || plain.isEmpty()) return;
        SpannableStringBuilder text = new SpannableStringBuilder(plain);
        view.setText(text, TextView.BufferType.SPANNABLE);
        Spannable live = (Spannable) view.getText();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setDuration(1600);
        animator.setInterpolator(new LinearInterpolator());
        Wave wave = new Wave(animator, plain, live);
        animator.addUpdateListener(update -> paint(live, (float) update.getAnimatedValue()));
        ACTIVE.put(view, wave);
        paint(live, 0f);
        animator.start();
    }

    static void stop(TextView view) {
        Wave wave = ACTIVE.remove(view);
        if (wave != null) wave.animator.cancel();
        if (wave != null && view.getText() == wave.live) view.setText(wave.plain);
        view.getPaint().setShader(null);
        view.invalidate();
    }

    static void stopAll() {
        for (TextView view : new ArrayList<>(ACTIVE.keySet())) stop(view);
    }

    private static void paint(Spannable text, float phase) {
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) {
            text.removeSpan(span);
        }
        int length = text.length();
        int buckets = Math.min(length, 48);
        for (int bucket = 0; bucket < buckets; bucket++) {
            int from = bucket * length / buckets;
            int to = (bucket + 1) * length / buckets;
            if (from >= to) continue;
            int color = LineShimmerColor.at(from + (to - from) / 2, length, phase);
            text.setSpan(new ForegroundColorSpan(color), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static final class Wave {
        private final ValueAnimator animator;
        private final String plain;
        private final Spannable live;

        private Wave(ValueAnimator animator, String plain, Spannable live) {
            this.animator = animator;
            this.plain = plain;
            this.live = live;
        }
    }
}
