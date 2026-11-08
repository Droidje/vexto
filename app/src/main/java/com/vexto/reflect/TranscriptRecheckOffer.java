package com.vexto.reflect;

import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/** Кнопка «Перепроверить» поверх ленты. Повторное нажатие вне карточки её закрывает. */
final class TranscriptRecheckOffer {
    private ReflectPageStyle style;
    private View scrim;
    private LinearLayout card;
    private ReflectTranscript.Line line;

    void install(FrameLayout host, ReflectPageStyle style, ReflectArtwork artwork,
                 Consumer<ReflectTranscript.Line> recheck, Consumer<ReflectTranscript.Line> more) {
        this.style = style;
        scrim = new View(host.getContext());
        scrim.setOnTouchListener(this::scrimTouch);
        scrim.setVisibility(View.GONE);
        host.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card = new LinearLayout(host.getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(style.px(8), style.px(8), style.px(8), style.px(8));
        card.setBackground(style.rounded(0xff141414, 28));
        card.setClickable(true);
        card.setElevation(style.px(16));
        card.setTranslationZ(style.px(16));
        card.addView(action("Перепроверить", ReflectPageStyle.ACCENT, artwork, line -> recheck.accept(line)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = style.px(8);
        card.addView(action("Ещё", 0xff2a2a2a, artwork, line -> more.accept(line)), gap);
        card.setVisibility(View.GONE);
        host.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    }

    void show(View row, ReflectTranscript.Line offered) {
        if (card == null || style == null) return;
        line = offered;
        scrim.setVisibility(View.VISIBLE);
        card.setVisibility(View.INVISIBLE);
        card.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.post(() -> {
            if (line != offered || card.getParent() == null) return;
            View host = (View) card.getParent();
            int[] rowAt = new int[2];
            int[] hostAt = new int[2];
            row.getLocationInWindow(rowAt);
            host.getLocationInWindow(hostAt);
            int y = rowAt[1] - hostAt[1] - card.getMeasuredHeight() - style.px(8);
            if (y < style.px(12)) y = rowAt[1] - hostAt[1] + row.getHeight() + style.px(8);
            int x = Math.max(style.px(16), (host.getWidth() - card.getMeasuredWidth()) / 2);
            card.setX(x);
            card.setY(Math.max(style.px(8), y));
            card.setVisibility(View.VISIBLE);
            card.bringToFront();
        });
    }

    private boolean scrimTouch(View view, MotionEvent event) {
        if (card != null && card.getVisibility() == View.VISIBLE) {
            int[] at = new int[2];
            card.getLocationOnScreen(at);
            float x = event.getRawX();
            float y = event.getRawY();
            if (x >= at[0] && x <= at[0] + card.getWidth() && y >= at[1] && y <= at[1] + card.getHeight()) {
                return false;
            }
        }
        if (event.getAction() == MotionEvent.ACTION_UP) hide();
        return true;
    }

    void hide() {
        line = null;
        if (scrim != null) scrim.setVisibility(View.GONE);
        if (card != null) card.setVisibility(View.GONE);
    }

    private TextView action(String label, int color, ReflectArtwork artwork,
                            Consumer<ReflectTranscript.Line> click) {
        TextView button = style.text(label, 15, Color.WHITE, artwork.medium);
        button.setGravity(Gravity.CENTER);
        button.setPadding(style.px(16), style.px(10), style.px(16), style.px(10));
        button.setBackground(style.pressable(color, 22));
        button.setOnClickListener(view -> {
            ReflectTranscript.Line target = line;
            hide();
            if (target != null) click.accept(target);
        });
        return button;
    }
}
