package com.vexto.reflect;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native conversation detail with a virtualized, scrollable transcript. */
final class ReflectTranscriptView extends FrameLayout {
    private static final Avatar GREEN = new Avatar("transcript_avatar_base_green", "transcript_avatar_green");
    private static final Avatar AMBER = new Avatar("transcript_avatar_base_amber", "transcript_avatar_amber");
    private static final Avatar ROSE = new Avatar("transcript_avatar_base_rose", "transcript_avatar_rose");
    private static final Avatar PREVIEW_PORTRAIT = new Avatar("transcript_avatar_base_green", null);
    private static final Avatar[] SPEAKER_AVATARS = {GREEN, AMBER, ROSE};
    private static final Avatar[] PREVIEW_AVATARS = {GREEN, AMBER,
            new Avatar("transcript_avatar_base_rose", "transcript_avatar_green"), ROSE,
            PREVIEW_PORTRAIT, PREVIEW_PORTRAIT};

    interface Actions {
        void closeTranscript();
        void openExportOptions();
        void importTranscript();
        void transcribeRecording();
        void cancelTranscription();
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
    private boolean hasTranscript;
    private RecordingMetadata metadata;
    private String title = "", length = "", issue;
    private List<ReflectTranscript.Line> lines = Collections.emptyList();
    private Map<String, String> speakerNames = Collections.emptyMap();
    private TextView play;
    private TextView progressText;
    private ProgressBar progressBar;
    private ReflectLocalTranscription.Progress transcription;
    private ListView list;

    ReflectTranscriptView(Context context, ReflectArtwork artwork, ReflectPreferences preferences, Actions actions) {
        super(context);
        this.artwork = artwork;
        this.preferences = preferences;
        this.actions = actions;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 47f / 393f);
            }
        });
        setClipToOutline(true);
    }

    void content(String title, String length, ReflectTranscript transcript, boolean preview,
                 boolean hasAudio, boolean loading, String issue, boolean recordingDetails,
                 RecordingMetadata metadata, ReflectLocalTranscription.Progress transcription) {
        if (!title.equals(this.title)) list = null;
        this.title = title;
        this.length = length;
        this.preview = preview;
        this.hasAudio = hasAudio;
        this.loading = loading;
        this.issue = issue;
        this.recordingDetails = recordingDetails;
        this.metadata = metadata;
        this.transcription = transcription;
        hasTranscript = transcript != null;
        lines = recordingDetails || transcript == null ? Collections.emptyList() : transcript.lines;
        refresh = true;
        requestLayout();
    }

    void transcription(ReflectLocalTranscription.Progress value) {
        boolean rebuild = transcription == null || value == null
                || transcription.cancellable() != value.cancellable();
        transcription = value;
        if (rebuild) refreshStyle();
        else updateProgress();
    }

    void refreshStyle() {
        refresh = true;
        requestLayout();
    }

    void speakerNames(Map<String, String> names) {
        if (speakerNames.equals(names)) return;
        speakerNames = names;
        refreshStyle();
    }

    void clear() {
        lines = Collections.emptyList();
        speakerNames = Collections.emptyMap();
        metadata = null;
        removeAllViews();
        list = null;
        play = null;
        progressText = null;
        progressBar = null;
        transcription = null;
        refresh = true;
    }

    void playing(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        if (play != null) play.setText(playing ? "Приостановить" : "Прослушать аудио");
    }

    boolean isPlaying() { return playing; }

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
        progressText = null;
        progressBar = null;
        list = new ListView(getContext());
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, style.px(86));
        list.addHeaderView(header(), null, false);
        list.setAdapter(new LineAdapter());
        FrameLayout.LayoutParams scroll = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        scroll.topMargin = style.px(recordingDetails ? 141 : 139);
        addView(list, scroll);
        list.setSelectionFromTop(first, offset);

        if (!recordingDetails) addTranscriptBackdrop();
        pill(16, 60, 48, 48, false);
        icon("transcript_back", "Назад", 16, 60, 48, 48, false, actions::closeTranscript);
        pill(281, 60, 96, 48, false);
        icon("transcript_export", "Поделиться", 281, 60, 48, 48, false, actions::openExportOptions);
        icon("transcript_more", recordingDetails ? "Действия записи" : "Действия разговора",
                329, 60, 48, 48, false, actions::openTranscriptOptions);
        if (recordingDetails) return;

        pill(16, 28, 144, 48, true);
        icon("transcript_more", "Действия транскрипта", 16, 28, 48, 48, true, actions::openTranscriptOptions);
        icon("transcript_add", "Импортировать транскрипт", 64, 28, 48, 48, true, actions::importTranscript);
        icon("transcript_style", "Настройки чтения", 112, 28, 48, 48, true, actions::openTranscriptStyle);
    }

    private void addTranscriptBackdrop() {
        View shade = new ReflectTranscriptBackdrop(getContext(), list, style.px(8));
        addView(shade, new FrameLayout.LayoutParams(MATCH_PARENT, style.px(115), Gravity.BOTTOM));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {}

            @Override public void onScroll(AbsListView view, int first, int count, int total) {
                shade.invalidate();
            }
        });
    }

    private LinearLayout header() {
        LinearLayout header = style.column();
        TextView heading = style.title(title);
        if (!recordingDetails) heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.px(22));
        heading.setPadding(style.px(16), 0, style.px(16), 0);
        style.append(header, heading, 0, lines.isEmpty() ? 14 : 30);
        if (hasAudio && lines.isEmpty()) {
            TextView label = style.text(length + " · Локальная запись", 14, ReflectPageStyle.MUTED, artwork.regular);
            label.setPadding(style.px(16), 0, style.px(16), 0);
            style.append(header, label, 0, 10);
            play = button(playing ? "Приостановить" : "Прослушать аудио", actions::playTranscriptAudio);
            addInset(header, play, 0, 16);
        }
        if (recordingDetails) {
            addInset(header, metadataCard(), 8, 12);
        } else if (lines.isEmpty()) {
            addInset(header, transcriptionCard(), 8, 12);
        }
        return header;
    }

    private LinearLayout transcriptionCard() {
        LinearLayout card = style.column();
        card.setBackground(style.rounded(ReflectPageStyle.CARD, 24));
        card.setPadding(style.px(16), style.px(16), style.px(16), style.px(16));
        if (transcription != null) {
            style.append(card, style.text("Расшифровываем на устройстве", 17, Color.WHITE, artwork.semibold), 0, 8);
            progressText = style.text("", 15, ReflectPageStyle.MUTED, artwork.regular);
            progressBar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.setProgressTintList(ColorStateList.valueOf(ReflectPageStyle.ACCENT));
            style.append(card, progressText, 0, 8);
            style.append(card, progressBar, 0, 12);
            updateProgress();
            if (transcription.cancellable()) {
                style.append(card, button("Отменить расшифровку", actions::cancelTranscription), 0, 0);
            }
            return card;
        }

        String heading = loading ? "Загружаем транскрипт"
                : hasTranscript ? "В транскрипте нет реплик" : "Транскрипта пока нет";
        style.append(card, style.text(heading, 17, Color.WHITE, artwork.semibold), 0, 8);
        if (!loading) {
            String message = issue != null ? issue : hasAudio && !hasTranscript
                    ? "Распознайте речь на устройстве или импортируйте JSON."
                    : "Импортируйте JSON с репликами, спикерами и таймкодами.";
            style.append(card, style.text(message, 15, ReflectPageStyle.MUTED, artwork.regular), 0, 12);
            if (hasAudio && !hasTranscript) {
                style.append(card, button("Расшифровать на устройстве", actions::transcribeRecording), 0, 8);
            }
            if (hasAudio) style.append(card, button("Импортировать JSON", actions::importTranscript), 0, 0);
        }
        return card;
    }

    private void updateProgress() {
        if (progressText == null || progressBar == null) return;
        progressText.setText(transcription.stage() + " · " + transcription.percent() + "%");
        progressBar.setProgress(transcription.percent());
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
        pill.setBackground(style.rounded(recordingDetails ? 0xff212121 : 0x1cffffff, 24));
        place(pill, left, y, width, height, bottom);
    }

    private void icon(String asset, String label, int left, int y, int width, int height, boolean bottom,
                      Runnable action) {
        ArtView button = new ArtView(asset);
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

    private record Avatar(String base, String picture) {}

    private final class AvatarView extends View {
        private Avatar avatar;

        AvatarView() {
            super(ReflectTranscriptView.this.getContext());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void show(Avatar value) {
            if (avatar == value) return;
            avatar = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (avatar == null) return;
            artwork.imagePixels(canvas, avatar.base(), 0, 0, getWidth(), getHeight());
            if (avatar.picture() != null) {
                artwork.imagePixels(canvas, avatar.picture(), 0, 0, getWidth(), getHeight());
            }
        }
    }

    private final class ArtView extends View {
        private final String asset;

        ArtView(String asset) {
            super(ReflectTranscriptView.this.getContext());
            this.asset = asset;
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            float scale = layoutWidth / 393f;
            canvas.scale(scale, scale);
            if ("transcript_more".equals(asset)) canvas.rotate(-90);
            artwork.centeredIcon(canvas, asset, 0, 0);
            canvas.restore();
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
            TranscriptRow row = recycled instanceof TranscriptRow existing ? existing : new TranscriptRow();
            row.bind(getItem(position), position);
            return row;
        }
    }

    private final class TranscriptRow extends LinearLayout {
        private final AvatarView avatar = new AvatarView();
        private final TextView metadata;
        private final TextView body;

        TranscriptRow() {
            super(ReflectTranscriptView.this.getContext());
            setPadding(style.px(16), 0, style.px(16), style.px(17));
            addView(avatar, new LinearLayout.LayoutParams(style.px(40), style.px(40)));
            LinearLayout words = style.column();
            LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
            width.leftMargin = style.px(12);
            addView(words, width);
            metadata = style.text("", 13, 0xff747474, artwork.regular);
            metadata.setLetterSpacing(-.01f);
            metadata.setTranslationY(-style.px(2));
            style.append(words, metadata, 0, 0);
            body = style.text("", preferences.textSize(), 0xffcccccc, artwork.regular);
            body.setLetterSpacing(-.01f);
            body.setTranslationY(-style.px(2));
            style.append(words, body, 0, 0);
        }

        void bind(ReflectTranscript.Line line, int position) {
            boolean designRow = preview && "Спринт по портфолио".equals(title);
            if (designRow && position < PREVIEW_AVATARS.length) avatar.show(PREVIEW_AVATARS[position]);
            else avatar.show(SPEAKER_AVATARS[Math.floorMod(line.speakerId.hashCode(), SPEAKER_AVATARS.length)]);
            SpannableStringBuilder label = new SpannableStringBuilder(
                    speakerNames.getOrDefault(line.speakerId, line.speakerId));
            if (preferences.timestamps()) {
                label.append(" ");
                int dot = label.length();
                label.append("· ").append(clock(line.startMs)).append("-").append(clock(line.endMs));
                label.setSpan(new StyleSpan(Typeface.BOLD), dot, dot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            metadata.setText(label);
            body.setLineHeight(style.px(preferences.textSize() * (position == 0 ? 1.3125f : 1.5f)));
            body.setText(line.text);
            setOnLongClickListener(v -> { actions.openTranscriptLine(line); return true; });
        }
    }

    private static String clock(int millis) {
        int seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
