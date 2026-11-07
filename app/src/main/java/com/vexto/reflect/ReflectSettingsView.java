package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.Consumer;

/** Reflect preferences with actual persisted controls and system microphone access. */
final class ReflectSettingsView extends ScrollView {
    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final Runnable openMicrophoneSettings;
    private int layoutWidth;

    ReflectSettingsView(Context context, ReflectArtwork artwork, ReflectPreferences preferences,
                        Runnable openMicrophoneSettings) {
        super(context);
        this.artwork = artwork;
        this.preferences = preferences;
        this.openMicrophoneSettings = openMicrophoneSettings;
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void refresh() {
        layoutWidth = 0;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width > 0 && width != layoutWidth) {
            layoutWidth = width;
            rebuild();
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void rebuild() {
        removeAllViews();
        ReflectPageStyle style = new ReflectPageStyle(getContext(), artwork, layoutWidth);
        LinearLayout root = style.column();
        root.setPadding(style.px(16), style.px(8), style.px(16),
                style.px(28 + ReflectView.NAV_BOTTOM_CLEARANCE));
        style.append(root, style.title("Настройки"), 0, 20);

        section(root, style, "Транскрипт");
        LinearLayout transcript = card(style);
        style.append(transcript, style.text("Размер текста", 17, Color.WHITE, artwork.semibold), 2, 12);
        style.append(transcript, textSizes(style), 0, 10);
        style.append(transcript, toggle(style, "Метки времени", preferences.timestamps(),
                preferences::setTimestamps), 0, 0);
        style.append(root, transcript, 0, 22);

        section(root, style, "Интерфейс");
        LinearLayout display = card(style);
        style.append(display, toggle(style, "Уменьшить движение", preferences.reduceMotion(),
                preferences::setReduceMotion), 0, 0);
        style.append(root, display, 0, 22);

        section(root, style, "Данные");
        LinearLayout storage = card(style);
        style.append(storage, style.text("Записи и профили хранятся на этом устройстве", 15,
                ReflectPageStyle.MUTED, artwork.regular), 0, 8);
        style.append(root, storage, 0, 22);

        section(root, style, "Приложение");
        LinearLayout application = card(style);
        TextView microphone = style.text("Доступ к микрофону  ›", 17, Color.WHITE, artwork.regular);
        microphone.setMinHeight(style.px(44));
        microphone.setBackground(style.pressable(ReflectPageStyle.CARD, 12));
        microphone.setOnClickListener(v -> openMicrophoneSettings.run());
        style.append(application, microphone, 0, 4);
        String version = getContext().getString(R.string.app_version_label,
                getContext().getString(R.string.app_name), BuildConfig.VERSION_NAME);
        style.append(application, style.text(version, 14,
                ReflectPageStyle.MUTED, artwork.regular), 8, 4);
        style.append(root, application, 0, 0);
        addView(root);
    }

    private void section(LinearLayout root, ReflectPageStyle style, String title) {
        style.append(root, style.text(title, 15, ReflectPageStyle.MUTED, artwork.semibold), 0, 8);
    }

    private LinearLayout card(ReflectPageStyle style) {
        LinearLayout card = style.column();
        card.setBackground(style.rounded(ReflectPageStyle.CARD, 24));
        card.setPadding(style.px(16), style.px(10), style.px(16), style.px(10));
        return card;
    }

    private Switch toggle(ReflectPageStyle style, String label, boolean checked, Consumer<Boolean> change) {
        Switch toggle = new Switch(getContext()) {
            @Override public void setChecked(boolean value) {
                super.setChecked(value);
                if (isLaidOut() && !preferences.motionEnabled()) jumpDrawablesToCurrentState();
            }
        };
        toggle.setText(label);
        toggle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, style.px(17));
        toggle.setTypeface(artwork.regular);
        toggle.setTextColor(Color.WHITE);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(style.px(48));
        toggle.setSwitchMinWidth(style.px(56));
        toggle.setSwitchPadding(style.px(12));
        toggle.setShowText(false);
        toggle.setSplitTrack(false);
        var track = style.rounded(Color.WHITE, 14);
        track.setSize(style.px(56), style.px(28));
        toggle.setTrackDrawable(track);
        var thumb = style.rounded(Color.WHITE, 11);
        thumb.setSize(style.px(22), style.px(22));
        int inset = style.px(3);
        LayerDrawable insetThumb = new LayerDrawable(new Drawable[]{thumb});
        insetThumb.setLayerInset(0, inset, inset, inset, inset);
        toggle.setThumbDrawable(insetThumb);
        toggle.setChecked(checked);
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ReflectPageStyle.ACCENT, 0xff626262}));
        toggle.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, 0xffbcbcbc}));
        toggle.setOnCheckedChangeListener((button, value) -> change.accept(value));
        return toggle;
    }

    private LinearLayout textSizes(ReflectPageStyle style) {
        SizeOptions options = new SizeOptions(style);
        options.setBackground(style.rounded(0xff343434, 20));
        options.setPadding(style.px(3), style.px(3), style.px(3), style.px(3));
        String[] labels = {"Мелкий", "Обычный", "Крупный"};
        int[] sizes = {14, 16, 20};
        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            TextView item = style.text(labels[i], 14, Color.WHITE, artwork.medium);
            item.setGravity(Gravity.CENTER);
            item.setTag(size);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),
                    null, style.rounded(Color.WHITE, 17)));
            item.setOnClickListener(view -> {
                preferences.setTextSize(size);
                options.selectSize(true);
            });
            options.addView(item, new LinearLayout.LayoutParams(0, style.px(36), 1));
        }
        options.selectSize(false);
        return options;
    }

    private final class SizeOptions extends LinearLayout {
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int radius;
        private ValueAnimator animation;
        private int selected;
        private float selection;

        SizeOptions(ReflectPageStyle style) {
            super(ReflectSettingsView.this.getContext());
            radius = style.px(17);
            pill.setColor(0xff575757);
        }

        void selectSize(boolean animate) {
            int size = preferences.textSize();
            for (int i = 0; i < getChildCount(); i++) {
                View item = getChildAt(i);
                boolean match = (int) item.getTag() == size;
                item.setSelected(match);
                if (match) selected = i;
            }
            if (animation != null) animation.cancel();
            if (!animate || selection == selected || !preferences.motionEnabled()) {
                selection = selected;
                invalidate();
                return;
            }
            animation = ValueAnimator.ofFloat(selection, selected);
            animation.setDuration(190);
            animation.setInterpolator(ReflectMotion.EASE);
            animation.addUpdateListener(frame -> {
                selection = (float) frame.getAnimatedValue();
                invalidate();
            });
            animation.start();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            int from = (int) selection;
            int to = Math.min(from + 1, getChildCount() - 1);
            View first = getChildAt(from);
            if (first != null) {
                View next = getChildAt(to);
                float fraction = selection - from;
                float left = first.getLeft() + (next.getLeft() - first.getLeft()) * fraction;
                float right = first.getRight() + (next.getRight() - first.getRight()) * fraction;
                canvas.drawRoundRect(left, first.getTop(), right, first.getBottom(), radius, radius, pill);
            }
            super.dispatchDraw(canvas);
        }

        @Override protected void onDetachedFromWindow() {
            if (animation != null) {
                animation.cancel();
                animation = null;
            }
            selection = selected;
            super.onDetachedFromWindow();
        }
    }
}
