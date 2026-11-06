package com.vexto.reflect;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
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
        root.setPadding(style.px(16), style.px(8), style.px(16), style.px(28));
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

        section(root, style, "Приложение");
        LinearLayout application = card(style);
        TextView microphone = style.text("Доступ к микрофону  ›", 17, Color.WHITE, artwork.regular);
        microphone.setMinHeight(style.px(44));
        microphone.setBackground(style.pressable(ReflectPageStyle.CARD, 12));
        microphone.setOnClickListener(v -> openMicrophoneSettings.run());
        style.append(application, microphone, 0, 4);
        style.append(application, style.text("Reflect  " + BuildConfig.VERSION_NAME, 14,
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
        Switch toggle = new Switch(getContext());
        toggle.setText(label);
        toggle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, style.px(17));
        toggle.setTypeface(artwork.regular);
        toggle.setTextColor(Color.WHITE);
        toggle.setMinHeight(style.px(44));
        toggle.setChecked(checked);
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ReflectPageStyle.ACCENT, 0xff626262}));
        toggle.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, 0xffbcbcbc}));
        toggle.setOnCheckedChangeListener((button, value) -> change.accept(value));
        return toggle;
    }

    private LinearLayout textSizes(ReflectPageStyle style) {
        LinearLayout options = new LinearLayout(getContext());
        options.setBackground(style.rounded(0xff343434, 20));
        options.setPadding(style.px(3), style.px(3), style.px(3), style.px(3));
        String[] labels = {"Мелкий", "Обычный", "Крупный"};
        int[] sizes = {14, 16, 20};
        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            TextView item = style.text(labels[i], 14, Color.WHITE, artwork.medium);
            item.setGravity(Gravity.CENTER);
            item.setTag(size);
            item.setOnClickListener(view -> {
                preferences.setTextSize(size);
                selectSize(options, style);
            });
            options.addView(item, new LinearLayout.LayoutParams(0, style.px(36), 1));
        }
        selectSize(options, style);
        return options;
    }

    private void selectSize(LinearLayout options, ReflectPageStyle style) {
        for (int i = 0; i < options.getChildCount(); i++) {
            android.view.View item = options.getChildAt(i);
            boolean selected = (int) item.getTag() == preferences.textSize();
            item.setSelected(selected);
            item.setBackground(style.pressable(selected ? 0xff575757 : Color.TRANSPARENT, 17));
        }
    }
}
