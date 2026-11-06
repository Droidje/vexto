package com.vexto.reflect;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native conversation detail with a virtualized, scrollable transcript. */
final class ReflectTranscriptView extends FrameLayout {
    interface Actions {
        void closeTranscript();
        void openExportOptions();
        void importTranscript();
        void openTranscriptOptions();
        void openTranscriptStyle();
        void playTranscriptAudio();
        void openTranscriptLine(ReflectTranscript.Line line);
    }

    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final Actions actions;
    private ReflectPageStyle style;
    private int layoutWidth;
    private boolean refresh = true;
    private boolean preview, loading, hasAudio, playing, recordingDetails;
    private RecordingMetadata metadata;
    private String title = "", length = "", issue;
    private List<ReflectTranscript.Line> lines = Collections.emptyList();
    private TextView play;
    private ListView list;

    ReflectTranscriptView(Context context, ReflectArtwork artwork, ReflectPreferences preferences, Actions actions) {
        super(context);
        this.artwork = artwork;
        this.preferences = preferences;
        this.actions = actions;
    }

    void content(String title, String length, ReflectTranscript transcript, boolean preview,
                 boolean hasAudio, boolean loading, String issue, boolean recordingDetails,
                 RecordingMetadata metadata) {
        if (!title.equals(this.title)) list = null;
        this.title = title;
        this.length = length;
        this.preview = preview;
        this.hasAudio = hasAudio;
        this.loading = loading;
        this.issue = issue;
        this.recordingDetails = recordingDetails;
        this.metadata = metadata;
        lines = recordingDetails || transcript == null ? Collections.emptyList() : transcript.lines;
        refresh = true;
        requestLayout();
    }

    void refreshStyle() {
        refresh = true;
        requestLayout();
    }

    void clear() {
        lines = Collections.emptyList();
        metadata = null;
        removeAllViews();
        list = null;
        play = null;
        refresh = true;
    }

    void playing(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        if (play != null) play.setText(playing ? "Приостановить" : "Прослушать аудио");
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
        int first = list == null ? 0 : list.getFirstVisiblePosition();
        int offset = list == null || list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
        removeAllViews();
        play = null;
        list = new ListView(getContext());
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, style.px(86));
        list.addHeaderView(header(), null, false);
        list.setAdapter(new LineAdapter());
        FrameLayout.LayoutParams scroll = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        scroll.topMargin = style.px(141);
        addView(list, scroll);
        list.setSelectionFromTop(first, offset);

        pill(16, 60, 48, 48, false);
        icon("transcript_back", "Назад", 16, 60, 48, 48, false, actions::closeTranscript);
        pill(281, 60, 96, 48, false);
        icon("transcript_export", "Поделиться", 281, 60, 48, 48, false, actions::openExportOptions);
        icon("transcript_more", recordingDetails ? "Действия записи" : "Действия разговора",
                329, 60, 48, 48, false, actions::openTranscriptOptions);
        if (recordingDetails) return;

        View shade = new View(getContext());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00040402, 0xf0040402, 0xff040402}));
        FrameLayout.LayoutParams fade = new FrameLayout.LayoutParams(MATCH_PARENT, style.px(108),
                Gravity.BOTTOM);
        addView(shade, fade);
        pill(16, 28, 144, 48, true);
        icon("transcript_more", "Действия транскрипта", 16, 28, 48, 48, true, actions::openTranscriptOptions);
        icon("transcript_add", "Импортировать транскрипт", 64, 28, 48, 48, true, actions::importTranscript);
        icon("transcript_style", "Настройки чтения", 112, 28, 48, 48, true, actions::openTranscriptStyle);
    }

    private LinearLayout header() {
        LinearLayout header = style.column();
        TextView heading = style.title(title);
        heading.setPadding(style.px(16), 0, style.px(16), 0);
        style.append(header, heading, 0, 14);
        if (!lines.isEmpty()) {
            style.append(header, participants(), 0, 20);
        }
        if (hasAudio) {
            TextView label = style.text(length + " · Локальная запись", 14, ReflectPageStyle.MUTED, artwork.regular);
            label.setPadding(style.px(16), 0, style.px(16), 0);
            style.append(header, label, 0, 10);
            play = button(playing ? "Приостановить" : "Прослушать аудио", actions::playTranscriptAudio);
            addInset(header, play, 0, 16);
        }
        if (recordingDetails) {
            addInset(header, metadataCard(), 8, 12);
        } else if (lines.isEmpty()) {
            LinearLayout empty = style.column();
            empty.setBackground(style.rounded(ReflectPageStyle.CARD, 24));
            empty.setPadding(style.px(16), style.px(16), style.px(16), style.px(16));
            style.append(empty, style.text(loading ? "Загружаем транскрипт" : "Транскрипта пока нет",
                    17, Color.WHITE, artwork.semibold), 0, 8);
            String message = issue != null ? issue : "Импортируйте JSON с репликами, спикерами и таймкодами.";
            if (!loading) {
                style.append(empty, style.text(message, 15, ReflectPageStyle.MUTED, artwork.regular), 0, 12);
                if (hasAudio) style.append(empty, button("Импортировать JSON", actions::importTranscript), 0, 0);
            }
            addInset(header, empty, 8, 12);
        } else {
            View divider = new ArtView("transcript_divider", false);
            FrameLayout dividerBox = new FrameLayout(getContext());
            dividerBox.addView(divider, new FrameLayout.LayoutParams(style.px(361), style.px(2), Gravity.CENTER));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(MATCH_PARENT, style.px(2));
            dividerParams.bottomMargin = style.px(22);
            header.addView(dividerBox, dividerParams);
        }
        return header;
    }

    private LinearLayout metadataCard() {
        LinearLayout card = style.column();
        card.setBackground(style.rounded(ReflectPageStyle.CARD, 24));
        card.setPadding(style.px(16), style.px(16), style.px(16), style.px(8));
        TextView heading = style.text("О записи", 18, Color.WHITE, artwork.semibold);
        heading.setAccessibilityHeading(true);
        style.append(card, heading, 0, 8);
        if (metadata == null) {
            style.append(card, style.text("Загружаем данные записи", 15, ReflectPageStyle.MUTED, artwork.regular),
                    0, 8);
            return card;
        }
        metadataRow(card, metadata.dateLabel(), metadata.dateText());
        metadataRow(card, "Локация", metadata.location());
        metadataRow(card, "Формат", metadata.format());
        metadataRow(card, "Размер файла", Formatter.formatShortFileSize(getContext(), metadata.bytes()));
        if (metadata.bitrate() != null) metadataRow(card, "Битрейт", metadata.bitrate());
        return card;
    }

    private void metadataRow(LinearLayout card, String label, String value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(style.px(38));
        row.addView(style.text(label, 14, ReflectPageStyle.MUTED, artwork.regular),
                new LinearLayout.LayoutParams(style.px(124), WRAP_CONTENT));
        TextView data = style.text(value, 14, Color.WHITE, artwork.medium);
        data.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(data, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        style.append(card, row, 0, 0);
    }

    private HorizontalScrollView participants() {
        HorizontalScrollView strip = new HorizontalScrollView(getContext());
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout people = new LinearLayout(getContext());
        people.setPadding(style.px(16), 0, style.px(16), 0);
        Set<String> unique = new LinkedHashSet<>();
        if (preview && "Спринт по портфолио".equals(title)) Collections.addAll(unique, "jennifer", "alex", "michel");
        for (ReflectTranscript.Line line : lines) unique.add(line.speakerId);
        int visible = 0;
        for (String speaker : unique) {
            if (visible++ == 24) break;
            LinearLayout person = style.column();
            person.setGravity(Gravity.CENTER_HORIZONTAL);
            person.addView(avatar(speaker, true), new LinearLayout.LayoutParams(style.px(120), style.px(120)));
            String name = speakerName(speaker);
            if (preview && speaker.equals("jennifer")) name = "Jennifer\nColein";
            TextView label = style.text(name, 18, Color.WHITE, preview ? artwork.latinSemibold : artwork.semibold);
            label.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            style.append(person, label, 9, 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(style.px(120), WRAP_CONTENT);
            params.rightMargin = style.px(16);
            people.addView(person, params);
        }
        if (unique.size() > 24) {
            people.addView(style.text("+" + (unique.size() - 24), 18, ReflectPageStyle.MUTED, artwork.semibold));
        }
        strip.addView(people);
        return strip;
    }

    private TextView button(String text, Runnable action) {
        TextView button = style.text(text, 16, Color.WHITE, artwork.medium);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(style.px(44));
        button.setBackground(style.pressable(0xff353535, 24));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void addInset(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.setMargins(style.px(16), style.px(top), style.px(16), style.px(bottom));
        parent.addView(child, params);
    }

    private void pill(int left, int y, int width, int height, boolean bottom) {
        View pill = new View(getContext());
        pill.setBackground(style.rounded(0xff212121, 24));
        place(pill, left, y, width, height, bottom);
    }

    private void icon(String asset, String label, int left, int y, int width, int height, boolean bottom,
                      Runnable action) {
        ArtView button = new ArtView(asset, false);
        button.setContentDescription(label);
        button.setFocusable(true);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), null,
                style.rounded(Color.WHITE, 24)));
        button.setOnClickListener(v -> action.run());
        place(button, left, y, width, height, bottom);
    }

    private void place(View view, int left, int y, int width, int height, boolean bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(style.px(width), style.px(height),
                Gravity.LEFT | (bottom ? Gravity.BOTTOM : Gravity.TOP));
        params.leftMargin = style.px(left);
        if (bottom) params.bottomMargin = style.px(y);
        else params.topMargin = style.px(y);
        addView(view, params);
    }

    private View avatar(String speaker, boolean large) {
        if (preview && ("jennifer".equals(speaker) || "alex".equals(speaker) || "michel".equals(speaker))) {
            return new ArtView((large ? "transcript_speaker_" : "transcript_row_") + speaker, true);
        }
        String name = speakerName(speaker);
        TextView initial = style.text(name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT),
                large ? 35 : 16, 0xffd4baff, artwork.display);
        initial.setGravity(Gravity.CENTER);
        initial.setBackground(style.rounded(0xff2c1740, 100));
        return initial;
    }

    private String speakerName(String speaker) {
        return preview ? switch (speaker) {
            case "jennifer" -> "Jennifer Colein";
            case "alex" -> "Alex";
            case "michel" -> "MichelYe";
            default -> speaker;
        } : speaker;
    }

    private final class ArtView extends View {
        private final String asset;
        private final boolean photo;

        ArtView(String asset, boolean photo) {
            super(ReflectTranscriptView.this.getContext());
            this.asset = asset;
            this.photo = photo;
        }

        @Override protected void onDraw(Canvas canvas) {
            if (photo) artwork.imagePixels(canvas, asset, 0, 0, getWidth(), getHeight());
            else {
                canvas.save();
                canvas.translate(getWidth() / 2f, getHeight() / 2f);
                float scale = layoutWidth / 393f;
                canvas.scale(scale, scale);
                if ("transcript_more".equals(asset)) canvas.rotate(-90);
                artwork.centeredIcon(canvas, asset, 0, 0);
                canvas.restore();
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (isClickable()) info.setClassName("android.widget.Button");
        }
    }

    private final class LineAdapter extends BaseAdapter {
        @Override public int getCount() { return lines.size(); }
        @Override public ReflectTranscript.Line getItem(int position) { return lines.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ReflectTranscript.Line line = getItem(position);
            LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(getContext());
            row.removeAllViews();
            row.setPadding(style.px(16), 0, style.px(16), style.px(28));
            row.addView(avatar(line.speakerId, false), new LinearLayout.LayoutParams(style.px(40), style.px(40)));
            LinearLayout words = style.column();
            LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
            width.leftMargin = style.px(12);
            row.addView(words, width);
            if (!preview) {
                String meta = speakerName(line.speakerId);
                if (preferences.timestamps()) meta += " · " + clock(line.startMs) + "–" + clock(line.endMs);
                style.append(words, style.text(meta, 13, ReflectPageStyle.MUTED, artwork.medium), 2, 4);
            }
            TextView body = style.text(line.text, preferences.textSize(), 0xffcccccc, artwork.regular);
            body.setLetterSpacing(-.01f);
            body.setLineSpacing(style.px(preview && position == 0 ? 0 : 2), 1);
            style.append(words, body, preview ? 8 : 0, 0);
            row.setOnLongClickListener(v -> { actions.openTranscriptLine(line); return true; });
            return row;
        }
    }

    private static String clock(int millis) {
        int seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
