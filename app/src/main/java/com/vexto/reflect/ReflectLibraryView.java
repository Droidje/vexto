package com.vexto.reflect;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.Collections;
import java.util.List;

/** Content above the shared navigation. Geometry follows the 393 dp design stage. */
final class ReflectLibraryView extends ViewGroup {
    enum Page { RECORDS, SPEAKERS, CONVERSATIONS }

    interface Actions {
        void openRecording(ReflectCatalog.Recording recording);
        void openConversation(ReflectCatalog.Conversation conversation);
        void inviteSpeakers();
    }

    private final ReflectArtwork artwork;
    private final Page page;
    private final Actions actions;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final LinearLayout rows;
    private final ScrollView scroller;
    private final EditText search;
    private final Button invite;
    private List<ReflectCatalog.Recording> recordings = Collections.emptyList();
    private List<ReflectCatalog.Speaker> speakers = Collections.emptyList();
    private List<ReflectCatalog.Conversation> conversations = Collections.emptyList();
    private float scale = 1;

    ReflectLibraryView(Context context, ReflectArtwork artwork, Page page, Actions actions) {
        super(context);
        this.artwork = artwork;
        this.page = page;
        this.actions = actions;
        setWillNotDraw(false);
        setFocusableInTouchMode(true);

        search = new EditText(context);
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
        search.setHint(page == Page.RECORDS ? "Поиск по участникам, тегам" : "Поиск по имени, id");
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
        addView(search);

        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroller = new ScrollView(context);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        scroller.addView(rows);
        addView(scroller);

        invite = new Button(context);
        invite.setAllCaps(false);
        invite.setText("Отправить ссылку");
        invite.setTextColor(0xff040402);
        invite.setTypeface(artwork.medium);
        invite.setLetterSpacing(0);
        invite.setPadding(0, 0, 0, 0);
        invite.setIncludeFontPadding(false);
        invite.setBackgroundTintList(null);
        invite.setStateListAnimator(null);
        invite.setOnClickListener(v -> actions.inviteSpeakers());
        addView(invite);
        updateContent();
        requestFocus();
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
        search.setVisibility(empty || page == Page.CONVERSATIONS ? GONE : VISIBLE);
        scroller.setVisibility(empty ? GONE : VISIBLE);
        invite.setVisibility(empty ? VISIBLE : GONE);
        renderRows(false);
        invalidate();
    }

    void dismissKeyboard() {
        if (!search.hasFocus()) return;
        InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
        requestFocus();
    }

    private void renderRows(boolean scrollToTop) {
        rows.removeAllViews();
        String query = search.getText().toString();
        switch (page) {
            case RECORDS -> renderRecordings(query);
            case SPEAKERS -> {
                for (ReflectCatalog.Speaker speaker : speakers) {
                    if (ReflectCatalog.matches(speaker.name + " " + speaker.id, query)) {
                        rows.addView(ReflectLibraryRow.speaker(getContext(), artwork, speaker));
                    }
                }
            }
            case CONVERSATIONS -> {
                for (ReflectCatalog.Conversation conversation : conversations) {
                    rows.addView(ReflectLibraryRow.conversation(getContext(), artwork, conversation,
                            () -> actions.openConversation(conversation)));
                }
            }
        }
        if (rows.getChildCount() == 0 && !emptySpeakers() && page != Page.CONVERSATIONS) {
            String message = query.trim().isEmpty() ? "Сохранённых записей пока нет" : "Ничего не найдено";
            rows.addView(ReflectLibraryRow.message(getContext(), artwork, message));
        }
        if (scrollToTop) scroller.scrollTo(0, 0);
    }

    private void renderRecordings(String query) {
        String section = null;
        for (ReflectCatalog.Recording recording : recordings) {
            if (!ReflectCatalog.matches(recording.search, query)) continue;
            if (!recording.date.equals(section)) {
                section = recording.date;
                rows.addView(ReflectLibraryRow.heading(getContext(), artwork, section));
            }
            rows.addView(ReflectLibraryRow.recording(getContext(), artwork, recording,
                    () -> { dismissKeyboard(); actions.openRecording(recording); }));
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        scale = width / 393f;
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * scale);
        search.setPadding(px(48), 0, px(12), 0);
        search.measure(exact(px(361)), exact(px(48)));
        scroller.measure(exact(width), exact(Math.max(0, height - px(scrollTop()))));
        if (emptySpeakers()) {
            invite.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * scale);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(Color.WHITE);
            pill.setCornerRadius(px(24));
            invite.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), pill, null));
            invite.measure(exact(px(195)), exact(px(48)));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        search.layout(px(16), px(51), px(377), px(99));
        scroller.layout(0, px(scrollTop()), getWidth(), getHeight());
        if (emptySpeakers()) invite.layout(px(100), px(446), px(295), px(494));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scale, scale);
        if (emptySpeakers()) {
            artwork.icon(canvas, "invite_cards", 100.81f, 121.1f);
            ReflectLibraryRow.style(paint, 22, 0xfff5f5f5, artwork.display, -.22f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Позови своих друзей!", 197.5f, 419, paint);
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
