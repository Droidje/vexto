package com.vexto.reflect;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Content above the shared navigation. Geometry follows the 393 dp design stage. */
final class ReflectLibraryView extends ViewGroup {
    private static final int SPEAKER_BUTTON_HEIGHT = 48;
    private static final int SPEAKER_BUTTON_BOTTOM_INSET = 128;

    enum Page { RECORDS, SPEAKERS, CONVERSATIONS }

    interface Actions {
        void openRecording(ReflectCatalog.Recording recording);
        void openConversation(ReflectCatalog.Conversation conversation);
        void addSpeaker();
        void openSpeaker(ReflectCatalog.Speaker speaker);
    }

    private final ReflectArtwork artwork;
    private final Page page;
    private final Actions actions;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final ListView scroller;
    private final View scrollEndSpace;
    private final RowAdapter adapter;
    private final EditText search;
    private final Button addSpeaker;
    private List<ReflectCatalog.Recording> recordings = Collections.emptyList();
    private List<ReflectCatalog.Speaker> speakers = Collections.emptyList();
    private List<ReflectCatalog.Conversation> conversations = Collections.emptyList();
    private float scale = 1;
    private int measuredWidth = -1;

    ReflectLibraryView(Context context, ReflectArtwork artwork, Page page, Actions actions) {
        super(context);
        this.artwork = artwork;
        this.page = page;
        this.actions = actions;
        setWillNotDraw(false);
        setFocusableInTouchMode(true);

        scroller = new ListView(context);
        scroller.setDivider(null);
        scroller.setDividerHeight(0);
        scroller.setSelector(new ColorDrawable(Color.TRANSPARENT));
        scroller.setItemsCanFocus(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        scrollEndSpace = new View(context);
        scrollEndSpace.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        scroller.addFooterView(scrollEndSpace, null, false);
        adapter = new RowAdapter();
        scroller.setAdapter(adapter);

        search = page == Page.CONVERSATIONS ? null : createSearch(context);
        addSpeaker = page == Page.SPEAKERS ? speakerButton(context, "Добавить спикера", actions::addSpeaker) : null;
        if (search != null) addView(search);
        addView(scroller);
        if (addSpeaker != null) addView(addSpeaker);
        updateContent();
        requestFocus();
    }

    private EditText createSearch(Context context) {
        EditText search = new EditText(context);
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        search.setPadding(0, 0, 0, 0);
        search.setBackground(null);
        search.setIncludeFontPadding(false);
        search.setTypeface(artwork.regular);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xff808080);
        search.setHint(page == Page.RECORDS ? "Поиск по записи, участникам" : "Поиск по имени, id");
        search.setOnEditorActionListener((field, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dismissKeyboard();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderRows(true); }
            @Override public void afterTextChanged(Editable s) {}
        });
        return search;
    }

    private Button speakerButton(Context context, String label, Runnable action) {
        Button invite = new Button(context);
        invite.setAllCaps(false);
        invite.setText(label);
        invite.setTextColor(0xff040402);
        invite.setTypeface(artwork.medium);
        invite.setLetterSpacing(0);
        invite.setPadding(0, 0, 0, 0);
        invite.setIncludeFontPadding(false);
        invite.setBackgroundTintList(null);
        invite.setStateListAnimator(null);
        invite.setOnClickListener(v -> action.run());
        return invite;
    }

    void setRecordings(List<ReflectCatalog.Recording> recordings) {
        this.recordings = recordings;
        renderRows(false);
    }

    void setSpeakers(List<ReflectCatalog.Speaker> speakers) {
        this.speakers = speakers;
        updateContent();
    }

    void setConversations(List<ReflectCatalog.Conversation> conversations) {
        this.conversations = conversations;
        renderRows(false);
    }

    boolean hasConversations() { return !conversations.isEmpty(); }

    private boolean emptySpeakers() { return page == Page.SPEAKERS && speakers.isEmpty(); }

    private void updateContent() {
        boolean empty = emptySpeakers();
        if (search != null) search.setVisibility(empty ? GONE : VISIBLE);
        scroller.setVisibility(empty ? GONE : VISIBLE);
        renderRows(false);
        invalidate();
    }

    void dismissKeyboard() {
        if (search == null || !search.hasFocus()) return;
        InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
        requestFocus();
    }

    private void renderRows(boolean scrollToTop) {
        adapter.rows.clear();
        String query = search == null ? "" : search.getText().toString();
        switch (page) {
            case RECORDS -> collectRecordings(query);
            case SPEAKERS -> {
                for (ReflectCatalog.Speaker speaker : speakers) {
                    if (ReflectCatalog.matches(speaker.name + " " + speaker.id, query)) {
                        adapter.rows.add(speaker);
                    }
                }
            }
            case CONVERSATIONS -> adapter.rows.addAll(conversations);
        }
        if (adapter.rows.isEmpty() && !emptySpeakers() && page != Page.CONVERSATIONS) {
            String message = query.trim().isEmpty() ? "Сохранённых записей пока нет" : "Ничего не найдено";
            adapter.rows.add(new TextRow(message, false));
        }
        adapter.notifyDataSetChanged();
        if (scrollToTop) scroller.setSelection(0);
    }

    private void collectRecordings(String query) {
        String section = null;
        for (ReflectCatalog.Recording recording : recordings) {
            if (!ReflectCatalog.matches(recording.search, query)) continue;
            if (!recording.date.equals(section)) {
                section = recording.date;
                adapter.rows.add(new TextRow(section, true));
            }
            adapter.rows.add(recording);
        }
    }

    private static final class TextRow {
        final String text;
        final boolean heading;

        TextRow(String text, boolean heading) {
            this.text = text;
            this.heading = heading;
        }
    }

    private final class RowAdapter extends BaseAdapter {
        final List<Object> rows = new ArrayList<>();

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override public boolean isEnabled(int position) {
            Object item = getItem(position);
            return item instanceof ReflectCatalog.Recording || item instanceof ReflectCatalog.Conversation
                    || item instanceof ReflectCatalog.Speaker;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Object item = getItem(position);
            ReflectLibraryRow row = ReflectLibraryRow.reuse(getContext(), recycled);
            if (item instanceof ReflectCatalog.Recording recording) {
                return row.recording(artwork, recording, () -> {
                    dismissKeyboard();
                    actions.openRecording(recording);
                });
            }
            if (item instanceof ReflectCatalog.Speaker speaker) {
                return row.speaker(artwork, speaker, () -> actions.openSpeaker(speaker));
            }
            if (item instanceof ReflectCatalog.Conversation conversation) {
                return row.conversation(artwork, conversation, () -> actions.openConversation(conversation));
            }
            TextRow label = (TextRow) item;
            return label.heading ? row.heading(artwork, label.text) : row.message(artwork, label.text);
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        if (width != measuredWidth) {
            measuredWidth = width;
            scale = width / 393f;
            int bottomInset = addSpeaker == null ? ReflectView.NAV_BOTTOM_CLEARANCE
                    : SPEAKER_BUTTON_BOTTOM_INSET + SPEAKER_BUTTON_HEIGHT + 12;
            scrollEndSpace.setLayoutParams(new AbsListView.LayoutParams(
                    LayoutParams.MATCH_PARENT, px(bottomInset)));
            if (search != null) {
                search.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * scale);
                search.setPadding(px(48), 0, px(12), 0);
            }
            if (addSpeaker != null) styleSpeakerButton(addSpeaker);
        }
        if (search != null && search.getVisibility() != GONE) search.measure(exact(px(361)), exact(px(48)));
        if (scroller.getVisibility() != GONE) scroller.measure(exact(width),
                exact(Math.max(0, height - px(scrollTop()))));
        if (addSpeaker != null) addSpeaker.measure(exact(px(195)), exact(px(SPEAKER_BUTTON_HEIGHT)));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (search != null && search.getVisibility() != GONE) search.layout(px(16), px(51), px(377), px(99));
        if (scroller.getVisibility() != GONE) scroller.layout(0, px(scrollTop()), getWidth(),
                Math.max(px(scrollTop()), getHeight()));
        if (addSpeaker != null) {
            int bottom = emptySpeakers() ? px(494) : getHeight() - px(SPEAKER_BUTTON_BOTTOM_INSET);
            addSpeaker.layout(px(100), bottom - addSpeaker.getMeasuredHeight(), px(295), bottom);
        }
    }

    private void styleSpeakerButton(Button button) {
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * scale);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(px(24));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), pill, null));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scale, scale);
        if (emptySpeakers()) {
            artwork.icon(canvas, "invite_cards", 100.81f, 121.1f);
            ReflectLibraryRow.style(paint, 22, 0xfff5f5f5, artwork.display, -.22f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Добавьте спикера", 197.5f, 419, paint);
        } else {
            String title = switch (page) {
                case RECORDS -> "Ваши записи";
                case SPEAKERS -> "Все участники";
                case CONVERSATIONS -> "Ваши разговоры";
            };
            ReflectLibraryRow.text(canvas, paint, title, 16, 27, 24, Color.WHITE, artwork.display, -.24f);
            if (page != Page.CONVERSATIONS) {
                paint.setColor(0xff333333);
                canvas.drawRoundRect(16, 51, 377, 99, 24, 24, paint);
                artwork.icon(canvas, "search", 28, 63);
            }
        }
        canvas.restore();
    }

    private int px(float design) { return Math.round(design * scale); }
    private int scrollTop() {
        return switch (page) {
            case RECORDS -> 125;
            case SPEAKERS -> 131;
            case CONVERSATIONS -> 51;
        };
    }
    private static int exact(int size) { return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY); }
}
