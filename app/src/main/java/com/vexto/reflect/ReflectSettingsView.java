package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.vexto.engine.model.ProcessingMode;
import java.util.function.Consumer;

/** Reflect preferences with actual persisted controls and system microphone access. */
final class ReflectSettingsView extends ScrollView {
    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final Runnable openMicrophoneSettings;
    private int layoutWidth;
    private TextView correctorHint;

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

        section(root, style, "Обработка записи");
        style.append(root, processingModes(style), 0, 22);

        section(root, style, "Транскрипт");
        LinearLayout transcript = card(style);
        style.append(transcript, style.text("Размер текста", 17, Color.WHITE, artwork.semibold), 2, 12);
        style.append(transcript, textSizes(style), 0, 10);
        style.append(transcript, style.text("Корректор", 17, Color.WHITE, artwork.semibold), 8, 4);
        style.append(transcript, correctorModes(style), 0, 6);
        correctorHint = style.text(correctorHint(preferences.punctuation()), 14, ReflectPageStyle.MUTED, artwork.regular);
        style.append(transcript, correctorHint, 0, 10);
        style.append(transcript, toggle(style, "Метки времени", preferences.timestamps(), false,
                preferences::setTimestamps), 0, 0);
        style.append(root, transcript, 0, 22);

        section(root, style, "Интерфейс");
        LinearLayout display = card(style);
        style.append(display, toggle(style, "Уменьшить движение", preferences.reduceMotion(), true,
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

    private LinearLayout processingModes(ReflectPageStyle style) {
        LinearLayout processing = card(style);
        TextView choice = style.text("", 17, Color.WHITE, artwork.regular);
        choice.setMinHeight(style.px(48));
        choice.setBackground(style.pressable(ReflectPageStyle.CARD, 12));
        choice.setFocusable(true);
        TextView description = style.text("", 14, ReflectPageStyle.MUTED, artwork.regular);
        Runnable refresh = () -> {
            ProcessingMode mode = preferences.processingMode();
            choice.setText("Режим: " + modeLabel(mode) + "  ›");
            description.setText(switch (mode) {
                case AUTO -> "Для записей длиннее 5 минут — быстрый режим, для остальных — качественный. "
                        + "В быстром режиме шумоподавление выключено.";
                case FAST -> "Облегчённые модели. Шумоподавление выключено.";
                case QUALITY -> "Полные модели и шумоподавление.";
            });
        };
        choice.setOnClickListener(view -> {
            ProcessingMode[] modes = {ProcessingMode.AUTO, ProcessingMode.FAST, ProcessingMode.QUALITY};
            String[] labels = {"Авто", "Быстрый", "Качественный"};
            ReflectActionSheet menu = new ReflectActionSheet((Activity) getContext(), style, preferences,
                    "Режим обработки");
            for (int index = 0; index < modes.length; index++) {
                ProcessingMode mode = modes[index];
                menu.option((mode == preferences.processingMode() ? "✓  " : "") + labels[index], () -> {
                    preferences.setProcessingMode(mode);
                    refresh.run();
                });
            }
            menu.option("Отмена", () -> {});
            menu.show();
        });
        refresh.run();
        style.append(processing, choice, 0, 8);
        style.append(processing, description, 0, 8);
        return processing;
    }

    private static String modeLabel(ProcessingMode mode) {
        return switch (mode) {
            case AUTO -> "Авто";
            case FAST -> "Быстрый";
            case QUALITY -> "Качественный";
        };
    }

    private LinearLayout card(ReflectPageStyle style) {
        LinearLayout card = style.column();
        card.setBackground(style.rounded(ReflectPageStyle.CARD, 24));
        card.setPadding(style.px(16), style.px(10), style.px(16), style.px(10));
        return card;
    }

    private CompoundButton toggle(ReflectPageStyle style, String label, boolean checked,
                                  boolean motionSetting, Consumer<Boolean> change) {
        SettingSwitch toggle = new SettingSwitch(style, motionSetting);
        toggle.setText(label);
        toggle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, style.px(17));
        toggle.setTypeface(artwork.regular);
        toggle.setTextColor(Color.WHITE);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(style.px(48));
        toggle.setPaddingRelative(0, 0, style.px(68), 0);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, value) -> change.accept(value));
        return toggle;
    }

    private final class SettingSwitch extends CompoundButton {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackWidth, trackHeight, thumbRadius, thumbInset;
        private final boolean motionSetting;
        private float position;
        private ValueAnimator animation;

        SettingSwitch(ReflectPageStyle style, boolean motionSetting) {
            super(ReflectSettingsView.this.getContext());
            this.motionSetting = motionSetting;
            trackWidth = style.px(56);
            trackHeight = style.px(28);
            thumbRadius = style.px(11);
            thumbInset = style.px(3);
            setButtonDrawable((android.graphics.drawable.Drawable) null);
            setClickable(true);
            setFocusable(true);
        }

        @Override public CharSequence getAccessibilityClassName() {
            return android.widget.Switch.class.getName();
        }

        @Override public void setChecked(boolean checked) {
            boolean changed = checked != isChecked();
            super.setChecked(checked);
            if (!changed) return;
            if (animation != null) animation.cancel();
            float target = checked ? 1 : 0;
            // Let the motion preference itself acknowledge the tap in either direction.
            boolean animate = motionSetting ? ValueAnimator.areAnimatorsEnabled() : preferences.motionEnabled();
            if (!isLaidOut() || !animate) {
                position = target;
                invalidate();
                return;
            }
            animation = ValueAnimator.ofFloat(position, target);
            animation.setDuration(260);
            animation.setInterpolator(ReflectMotion.PAGE);
            animation.addUpdateListener(frame -> {
                position = (float) frame.getAnimatedValue();
                invalidate();
            });
            animation.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float left = rtl ? 0 : getWidth() - trackWidth;
            float centerY = getHeight() / 2f;
            float radius = trackHeight / 2f;
            paint.setColor(blend(0xff626262, ReflectPageStyle.ACCENT, position));
            canvas.drawRoundRect(left, centerY - radius, left + trackWidth, centerY + radius,
                    radius, radius, paint);
            float travel = trackWidth - 2 * (thumbRadius + thumbInset);
            float thumbX = left + thumbInset + thumbRadius + travel * (rtl ? 1 - position : position);
            paint.setColor(blend(0xffbcbcbc, Color.WHITE, position));
            canvas.drawCircle(thumbX, centerY, thumbRadius, paint);
        }

        @Override protected void onDetachedFromWindow() {
            if (animation != null) animation.cancel();
            position = isChecked() ? 1 : 0;
            super.onDetachedFromWindow();
        }

        private int blend(int from, int to, float fraction) {
            return Color.rgb(
                    Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * fraction),
                    Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * fraction),
                    Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction));
        }
    }

    private LinearLayout correctorModes(ReflectPageStyle style) {
        LinearLayout options = new LinearLayout(getContext());
        options.setBackground(style.rounded(0xff343434, 20));
        options.setPadding(style.px(3), style.px(3), style.px(3), style.px(3));
        String[] modes = {ReflectPreferences.PUNCTUATION_OFF, ReflectPreferences.PUNCTUATION_SELF,
                ReflectPreferences.PUNCTUATION_ALL};
        String[] labels = {"Откл", "Для меня", "Все спикеры"};
        String[] descriptions = {"Корректор выключен", "Корректор включён для меня",
                "Корректор включён для всех спикеров"};
        for (int i = 0; i < modes.length; i++) {
            String mode = modes[i];
            TextView item = style.text(labels[i], 13, Color.WHITE, artwork.medium);
            item.setGravity(Gravity.CENTER);
            item.setMaxLines(2);
            item.setTag(mode);
            item.setContentDescription(descriptions[i]);
            item.setOnClickListener(view -> {
                preferences.setPunctuation(mode);
                if (correctorHint != null) correctorHint.setText(correctorHint(mode));
                selectMode(options, style);
            });
            options.addView(item, new LinearLayout.LayoutParams(0, style.px(44), 1));
        }
        selectMode(options, style);
        return options;
    }

    private void selectMode(LinearLayout options, ReflectPageStyle style) {
        for (int i = 0; i < options.getChildCount(); i++) {
            android.view.View item = options.getChildAt(i);
            boolean selected = preferences.punctuation().equals(item.getTag());
            item.setSelected(selected);
            item.setBackground(style.pressable(selected ? 0xff575757 : Color.TRANSPARENT, 17));
        }
    }

    private static String correctorHint(String mode) {
        if (ReflectPreferences.PUNCTUATION_SELF.equals(mode)) {
            return "Знаки препинания и регистр у спикера с отметкой «Вы».";
        }
        if (ReflectPreferences.PUNCTUATION_ALL.equals(mode)) {
            return "Знаки препинания и регистр у всех спикеров.";
        }
        return "Расшифровка показывается как есть.";
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
