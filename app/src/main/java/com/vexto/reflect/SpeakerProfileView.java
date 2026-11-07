package com.vexto.reflect;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** On-device speaker profile with a scrollable index of the speaker's utterances. */
final class SpeakerProfileView extends FrameLayout {
    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final Runnable back;
    private final Runnable menu;
    private final Consumer<SpeakerDirectory.Utterance> openUtterance;
    private SpeakerDirectory.Detail detail;
    private ReflectPageStyle style;
    private ListView list;
    private int width;
    private boolean rebuild = true;
    private boolean revealProfile;

    SpeakerProfileView(Context context, ReflectArtwork artwork, ReflectPreferences preferences,
                       Runnable back, Runnable menu,
                       Consumer<SpeakerDirectory.Utterance> openUtterance) {
        super(context);
        this.artwork = artwork;
        this.preferences = preferences;
        this.back = back;
        this.menu = menu;
        this.openUtterance = openUtterance;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 47f / 393f);
            }
        });
        setClipToOutline(true);
    }

    void content(SpeakerDirectory.Detail next) {
        if (next == detail) return;
        boolean changedProfile = next == null || detail == null || !next.profile().id().equals(detail.profile().id());
        if (changedProfile) list = null;
        revealProfile = next != null && changedProfile;
        detail = next;
        rebuild = true;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int measuredWidth = MeasureSpec.getSize(widthSpec);
        if (measuredWidth > 0 && (rebuild || measuredWidth != width)) {
            width = measuredWidth;
            rebuild = false;
            style = new ReflectPageStyle(getContext(), artwork, width);
            rebuildChildren();
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void rebuildChildren() {
        int first = list == null ? 0 : list.getFirstVisiblePosition();
        int offset = list == null || list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
        removeAllViews();
        list = new ListView(getContext());
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(style.px(16), 0, style.px(16), style.px(24));
        LinearLayout profileHeader = header();
        list.addHeaderView(profileHeader, null, false);
        list.setAdapter(new UtteranceAdapter(detail == null ? List.of() : detail.utterances()));
        list.setOnItemClickListener((parent, row, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item instanceof SpeakerDirectory.Utterance utterance) openUtterance.accept(utterance);
        });
        FrameLayout.LayoutParams body = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        body.topMargin = style.px(139);
        addView(list, body);
        list.setSelectionFromTop(Math.min(first, list.getCount() - 1), offset);
        if (revealProfile && isShown() && preferences.motionEnabled()) {
            profileHeader.setAlpha(.65f);
            profileHeader.setTranslationY(style.px(5));
            profileHeader.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(ReflectMotion.EASE).start();
        }
        revealProfile = false;

        toolbarButton("transcript_back", "Назад", Gravity.LEFT, back);
        if (detail != null) toolbarButton("transcript_more", "Действия спикера", Gravity.RIGHT, menu);
    }

    private LinearLayout header() {
        LinearLayout header = style.column();
        if (detail == null) {
            style.append(header, style.title("Спикер"), 0, 14);
            style.append(header, style.text("Загружаем профиль", 15, ReflectPageStyle.MUTED, artwork.regular), 0, 0);
            return header;
        }

        var profile = detail.profile();
        TextView name = style.title(ReflectCatalog.speakerName(profile.name(), profile.self()));
        if (profile.self()) name.setTextColor(ReflectPageStyle.SELF_ACCENT);
        style.append(header, name, 0, 22);

        LinearLayout statistics = new LinearLayout(getContext());
        statistics.setBackground(style.rounded(ReflectPageStyle.CARD, 20));
        statistics.setPadding(style.px(6), style.px(13), style.px(6), style.px(13));
        stat(statistics, Integer.toString(detail.conversations()), "Разговоры");
        stat(statistics, Integer.toString(detail.utterances().size()), "Реплики");
        stat(statistics, time(detail.speechMs()), "Время речи");
        style.append(header, statistics, 0, 24);

        TextView heading = style.text("Реплики", 19, Color.WHITE, artwork.semibold);
        heading.setAccessibilityHeading(true);
        style.append(header, heading, 0, 12);
        if (detail.utterances().isEmpty()) {
            style.append(header, style.text("У спикера пока нет реплик", 15, ReflectPageStyle.MUTED,
                    artwork.regular), 0, 0);
        }
        return header;
    }

    private void stat(LinearLayout statistics, String value, String label) {
        LinearLayout metric = style.column();
        metric.setPadding(style.px(4), 0, style.px(4), 0);
        TextView number = style.text(value, 22, Color.WHITE, artwork.semibold);
        number.setGravity(Gravity.CENTER);
        style.append(metric, number, 0, 4);
        TextView caption = style.text(label, 12, ReflectPageStyle.MUTED, artwork.regular);
        caption.setGravity(Gravity.CENTER);
        style.append(metric, caption, 0, 0);
        statistics.addView(metric, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
    }

    private void toolbarButton(String asset, String label, int side, Runnable action) {
        View button = new View(getContext()) {
            @Override protected void onDraw(Canvas canvas) {
                int saved = canvas.save();
                canvas.translate(getWidth() / 2f, getHeight() / 2f);
                canvas.scale(width / 393f, width / 393f);
                if ("transcript_more".equals(asset)) canvas.rotate(-90);
                artwork.centeredIcon(canvas, asset, 0, 0);
                canvas.restoreToCount(saved);
            }

            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.Button");
            }
        };
        button.setContentDescription(label);
        button.setFocusable(true);
        button.setBackground(style.pressable(0xff212121, 24));
        button.setOnClickListener(view -> action.run());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(style.px(48), style.px(48), side | Gravity.TOP);
        params.setMargins(style.px(16), style.px(60), style.px(16), 0);
        addView(button, params);
    }

    private static String time(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private final class UtteranceAdapter extends BaseAdapter {
        private final List<SpeakerDirectory.Utterance> utterances;

        UtteranceAdapter(List<SpeakerDirectory.Utterance> utterances) { this.utterances = utterances; }

        @Override public int getCount() { return utterances.size(); }
        @Override public SpeakerDirectory.Utterance getItem(int position) { return utterances.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            UtteranceRow row = recycled instanceof UtteranceRow existing ? existing : new UtteranceRow();
            row.bind(getItem(position));
            return row;
        }
    }

    private final class UtteranceRow extends LinearLayout {
        private final TextView source;
        private final TextView text;

        UtteranceRow() {
            super(SpeakerProfileView.this.getContext());
            setOrientation(VERTICAL);
            setPadding(0, style.px(7), 0, style.px(13));
            source = style.text("", 13, ReflectPageStyle.MUTED, artwork.medium);
            source.setMaxLines(2);
            source.setEllipsize(TextUtils.TruncateAt.END);
            style.append(this, source, 0, 5);
            text = style.text("", 16, Color.WHITE, artwork.regular);
            style.append(this, text, 0, 0);
        }

        void bind(SpeakerDirectory.Utterance utterance) {
            source.setText(time(utterance.startMs()) + "–" + time(utterance.endMs()) + " · " + utterance.title());
            text.setText(utterance.text());
        }
    }
}
