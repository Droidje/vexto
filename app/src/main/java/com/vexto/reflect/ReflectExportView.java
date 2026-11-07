package com.vexto.reflect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Figma export screen in the app's shared 393 × 852 stage. */
final class ReflectExportView extends FrameLayout {
    interface Actions {
        void closeExport();
        void selectExport(RecordingExport format);
        void shareExport();
        void saveExport();
    }

    private static final int SURFACE = 0xff2f2f2d, MUTED = 0xff999999, INK = 0xff040402;
    private static final Pattern JSON_VALUE = Pattern.compile(
            "\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final Actions actions;
    private ReflectPageStyle style;
    private String title = "", length = "", preview = "";
    private int lineCount, speakerCount, layoutWidth;
    private RecordingExport selected = RecordingExport.JSON;
    private boolean hasTranscript, canExport, canSelect, writing, refresh = true;
    private boolean animatePreview;

    ReflectExportView(Context context, ReflectArtwork artwork, ReflectPreferences preferences, Actions actions) {
        super(context);
        this.artwork = artwork;
        this.preferences = preferences;
        this.actions = actions;
        setBackgroundColor(INK);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 47f / 393f);
            }
        });
        setClipToOutline(true);
    }

    void content(String title, String length, ReflectTranscript transcript, RecordingExport selected,
                 String preview, boolean hasTranscript, boolean canExport, boolean canSelect, boolean writing) {
        this.title = title;
        this.length = length;
        animatePreview |= this.selected != selected && isShown() && preferences.motionEnabled();
        this.selected = selected;
        this.preview = preview;
        this.hasTranscript = hasTranscript;
        this.canExport = canExport;
        this.canSelect = canSelect;
        this.writing = writing;
        lineCount = transcript == null ? 0 : transcript.lines.size();
        HashSet<String> speakers = new HashSet<>();
        if (transcript != null) for (var line : transcript.lines) speakers.add(line.speakerId);
        speakerCount = speakers.size();
        refresh = true;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width > 0 && (refresh || width != layoutWidth)) {
            layoutWidth = width;
            refresh = false;
            style = new ReflectPageStyle(getContext(), artwork, width);
            rebuild();
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void rebuild() {
        removeAllViews();
        TextView back = button("", "Назад к расшифровке", actions::closeExport);
        back.setBackground(style.pressable(0xff20201e, 100));
        back.setCompoundDrawables(artwork.iconDrawable(getResources(), "export_back", style.px(24)), null, null, null);
        back.setPadding(style.px(12), 0, 0, 0);
        place(back, 16, 60, 48, 48);
        TextView heading = style.text("Экспорт расшифровки", 22, Color.WHITE, artwork.display);
        heading.setLetterSpacing(-.01f);
        heading.setSingleLine(true);
        heading.setAccessibilityHeading(true);
        place(heading, 74, 70, 309, 30);

        View avatar = new View(getContext()) {
            private final Path circle = new Path();
            { circle.addCircle(23, 23, 23, Path.Direction.CW); }
            @Override protected void onDraw(Canvas canvas) {
                float scale = getWidth() / 46f;
                canvas.save();
                canvas.scale(scale, scale);
                canvas.clipPath(circle);
                canvas.drawColor(Color.WHITE);
                // Figma image 3: mirrored horizontally, 51 × 65 inside a 46 × 46 avatar.
                canvas.scale(-1, 1, 23.5f, 22.5f);
                artwork.image(canvas, "conversation_sprint", -2, -10, 51, 65);
                canvas.restore();
            }
        };
        avatar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(avatar, 16, 138, 46, 46);
        TextView name = style.text(title, 18, Color.WHITE, artwork.semibold);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        place(name, 74, 129, 230, 22);
        TextView duration = style.text(length, 17, MUTED, artwork.duration);
        duration.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        place(duration, 310, 129, 67, 22);
        TextView counts = style.text(quantity(lineCount, "реплика", "реплики", "реплик") + "\n"
                + quantity(speakerCount, "спикер", "спикера", "спикеров"), 16, MUTED, artwork.regular);
        place(counts, 74, 153, 303, 40);
        View separator = new View(getContext());
        separator.setBackgroundColor(0xff10100e);
        place(separator, 74, 205, 319, 2);

        section("Формат", 227);
        format("JSON", RecordingExport.JSON, 16, 80);
        format("MARKDOWN", RecordingExport.MARKDOWN, 104, 131);
        format("ЗВУК", RecordingExport.AUDIO, 243, 79);
        section("Предпросмотр", 327);

        ScrollView scroll = new ScrollView(getContext());
        scroll.setBackground(style.rounded(SURFACE, 20));
        scroll.setClipToOutline(true);
        scroll.setPadding(style.px(20), style.px(16), style.px(20), style.px(16));
        scroll.setFillViewport(false);
        TextView text = style.text(selected == RecordingExport.JSON ? highlight(preview) : preview,
                20, 0xffadadac, artwork.regular);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setTextIsSelectable(true);
        text.setLineSpacing(0, 1.05f);
        scroll.addView(text, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        place(scroll, 16, 365, 361, 248);
        if (animatePreview && preferences.motionEnabled()) {
            text.setAlpha(.6f);
            text.setTranslationY(style.px(6));
            text.animate().alpha(1).translationY(0).setDuration(200).setInterpolator(ReflectMotion.EASE).start();
        }
        animatePreview = false;

        TextView share = button(writing ? "Подготовка файла…" : "Поделиться файлом", null, actions::shareExport);
        share.setTextColor(INK);
        share.setTypeface(artwork.medium);
        share.setBackground(style.pressable(Color.WHITE, 100));
        share.setEnabled(canExport);
        share.setAlpha(canExport ? 1 : .45f);
        place(share, 91, 744, 211, 48);
        TextView save = button("Сохранить в файл", null, actions::saveExport);
        save.setTextColor(MUTED);
        save.setEnabled(canExport);
        save.setAlpha(canExport ? 1 : .45f);
        place(save, 91, 794, 211, 44);
    }

    private void section(String title, int y) {
        TextView text = style.text(title, 22, Color.WHITE, artwork.medium);
        text.setAccessibilityHeading(true);
        place(text, 16, y, 361, 30);
    }

    private void format(String label, RecordingExport format, int x, int width) {
        TextView pill = button(label, label, () -> actions.selectExport(format));
        pill.setSelected(selected == format);
        pill.setEnabled(canSelect && (format == RecordingExport.AUDIO || hasTranscript));
        pill.setTextColor(selected == format ? INK : 0xfff0f0f0);
        pill.setTypeface(format == RecordingExport.AUDIO ? artwork.regular : artwork.duration);
        pill.setAlpha(format == RecordingExport.AUDIO || hasTranscript ? 1 : .4f);
        // 36 px visible pill, with a 48 px accessible touch target.
        pill.setBackground(new android.graphics.drawable.InsetDrawable(
                style.pressable(selected == format ? Color.WHITE : SURFACE, 100), 0, style.px(6), 0, style.px(6)));
        place(pill, x, 259, width, 48);
    }

    private TextView button(String title, String label, Runnable action) {
        TextView button = new TextView(getContext()) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.Button");
            }
        };
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, style.px(16));
        button.setTypeface(artwork.regular);
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(label);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void place(View child, int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(style.px(width), style.px(height));
        params.leftMargin = style.px(x);
        params.topMargin = style.px(y);
        addView(child, params);
    }

    static String quantity(int count, String one, String few, String many) {
        int tens = count % 100, units = count % 10;
        return count + " " + (tens >= 11 && tens <= 14 ? many : units == 1 ? one : units >= 2 && units <= 4 ? few : many);
    }

    private static CharSequence highlight(String text) {
        SpannableString styled = new SpannableString(text);
        Matcher token = JSON_VALUE.matcher(text);
        while (token.find()) {
            int next = token.end();
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
            if (next < text.length() && text.charAt(next) == ':') continue;
            styled.setSpan(new ForegroundColorSpan(text.charAt(token.start()) == '"' ? 0xff34c759 : 0xffac6a12),
                    token.start(), token.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }
}
