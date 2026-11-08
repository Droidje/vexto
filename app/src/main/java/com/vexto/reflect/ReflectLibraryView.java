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
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Content above the shared navigation. Geometry follows the 393 dp design stage. */
final class ReflectLibraryView extends ViewGroup {
    private static final int SPEAKER_BUTTON_HEIGHT = 48;
    private static final int SPEAKER_BUTTON_BOTTOM_INSET = 103;

    enum Page { RECORDS, SPEAKERS, CONVERSATIONS }

    interface Actions {
        void openRecording(ReflectCatalog.Recording recording);
        void openConversation(ReflectCatalog.Conversation conversation);
        Future<?> searchConversationQuotes(List<ReflectCatalog.Conversation> conversations, String query,
                                          Consumer<List<ReflectCatalog.Quote>> result);
        void openConversationQuote(ReflectCatalog.Quote quote);
        void addSpeaker();
        void openSpeaker(ReflectCatalog.Speaker speaker);
    }

    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
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
    private File processingRecording;
    private ReflectLocalTranscription.Progress processing;
    private Future<?> conversationSearch;
    private Runnable pendingSearch;
    private int searchGeneration;
    private boolean searchInside;
    private float scale = 1;
    private int measuredWidth = -1;

    ReflectLibraryView(Context context, ReflectArtwork artwork, ReflectPreferences preferences, Page page,
                       Actions actions) {
        super(context);
        this.artwork = artwork;
        this.preferences = preferences;
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

        search = createSearch(context);
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
        search.setHint(switch (page) {
            case RECORDS -> "Поиск по записи, участникам";
            case SPEAKERS -> "Поиск по имени, id";
            case CONVERSATIONS -> "Поиск по разговорам";
        });
        search.setOnEditorActionListener((field, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dismissKeyboard();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchInside = false;
                renderRows(true);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        return search;
    }

    private Button speakerButton(Context context, String label, Runnable action) {
        Button invite = new Button(context) {
            private final ReflectGlassBackdrop backdrop = new ReflectGlassBackdrop();

            @Override public void draw(Canvas canvas) {
                if (!emptySpeakers()) {
                    backdrop.draw(canvas, 0, 0, getWidth(), getHeight(), px(24), px(10), sample -> {
                        sample.translate(-getLeft(), -getTop());
                        ReflectLibraryView.this.drawChild(sample, scroller, getDrawingTime());
                    });
                }
                super.draw(canvas);
            }
        };
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

    @Override public void onDescendantInvalidated(View child, View target) {
        super.onDescendantInvalidated(child, target);
        if (addSpeaker != null && child == scroller) addSpeaker.invalidate();
    }

    void setRecordings(List<ReflectCatalog.Recording> recordings) {
        this.recordings = recordings;
        updateContent();
    }

    void setSpeakers(List<ReflectCatalog.Speaker> speakers) {
        this.speakers = speakers;
        updateContent();
        // The directory is retained while a profile is open. Rebind its existing rows now:
        // a return transition can draw it before ListView performs its next layout.
        for (int offset = 0; offset < scroller.getChildCount(); offset++) {
            int position = scroller.getFirstVisiblePosition() + offset;
            if (position >= adapter.getCount()) break;
            adapter.getView(position, scroller.getChildAt(offset), scroller);
        }
        scroller.invalidateViews();
    }

    void setConversations(List<ReflectCatalog.Conversation> conversations) {
        this.conversations = conversations;
        renderRows(false);
        scroller.invalidateViews();
    }

    void updateProgress(File recording, ReflectLocalTranscription.Progress progress) {
        File previous = processingRecording;
        if (progress == null && !recording.equals(previous)) return;
        processingRecording = progress == null ? null : recording;
        processing = progress;
        for (int offset = 0; offset < scroller.getChildCount(); offset++) {
            int position = scroller.getFirstVisiblePosition() + offset;
            if (position >= adapter.getCount()) break;
            Object item = adapter.getItem(position);
            File file = item instanceof ReflectCatalog.Recording row ? row.file
                    : item instanceof ReflectCatalog.Conversation row ? row.recording.file : null;
            if (file != null && (file.equals(recording) || file.equals(previous))) {
                adapter.getView(position, scroller.getChildAt(offset), scroller);
            }
        }
    }

    private ReflectLocalTranscription.Progress progress(File recording) {
        return recording.equals(processingRecording) ? processing : null;
    }

    boolean hasConversations() { return !conversations.isEmpty(); }

    private boolean emptySpeakers() { return page == Page.SPEAKERS && speakers.isEmpty(); }

    private boolean emptyRecords() { return page == Page.RECORDS && recordings.isEmpty(); }

    private void updateContent() {
        boolean empty = emptySpeakers() || emptyRecords();
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
                    if (ReflectCatalog.matches(speaker.displayName() + " " + speaker.name + " " + speaker.id, query)) {
                        adapter.rows.add(speaker);
                    }
                }
            }
            case CONVERSATIONS -> collectConversations(query);
        }
        if (adapter.rows.isEmpty() && !emptySpeakers() && !emptyRecords() && page != Page.CONVERSATIONS) {
            String message = query.trim().isEmpty() ? "Сохранённых записей пока нет" : "Ничего не найдено";
            adapter.rows.add(new TextRow(message, false));
        }
        adapter.notifyDataSetChanged();
        if (scrollToTop) scroller.setSelection(0);
    }

    private void collectConversations(String query) {
        int request = ++searchGeneration;
        if (pendingSearch != null) removeCallbacks(pendingSearch);
        if (conversationSearch != null) conversationSearch.cancel(true);
        if (query.trim().isEmpty()) {
            adapter.rows.addAll(conversations);
            return;
        }
        if (!searchInside) {
            adapter.rows.addAll(ReflectCatalog.searchConversations(conversations, query));
            if (adapter.rows.isEmpty()) {
                adapter.rows.add(new TextRow("Ничего не найдено", false));
                adapter.rows.add(new SearchInside());
            }
            return;
        }
        adapter.rows.add(new TextRow("Ищем в разговорах…", false));
        pendingSearch = () -> conversationSearch = actions.searchConversationQuotes(conversations, query, results -> {
            if (request != searchGeneration) return;
            adapter.rows.clear();
            adapter.rows.addAll(results);
            if (results.size() == 100) adapter.rows.add(new TextRow("Первые 100 совпадений. Уточните запрос.", false));
            if (results.isEmpty()) adapter.rows.add(new TextRow("В тексте разговоров совпадений нет", false));
            adapter.notifyDataSetChanged();
            scroller.animate().cancel();
            scroller.setAlpha(1f);
            if (isShown() && preferences.motionEnabled()) {
                scroller.setAlpha(.72f);
                scroller.animate().alpha(1f).setDuration(150).setInterpolator(ReflectMotion.EASE).start();
            }
        });
        postDelayed(pendingSearch, 180);
    }

    private static final class SearchInside {}

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
                    || item instanceof ReflectCatalog.Speaker || item instanceof ReflectCatalog.Quote
                    || item instanceof SearchInside;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Object item = getItem(position);
            ReflectLibraryRow row = ReflectLibraryRow.reuse(getContext(), recycled);
            if (item instanceof ReflectCatalog.Recording recording) {
                return row.recording(artwork, recording, progress(recording.file), () -> {
                    dismissKeyboard();
                    actions.openRecording(recording);
                });
            }
            if (item instanceof ReflectCatalog.Speaker speaker) {
                return row.speaker(artwork, speaker, () -> actions.openSpeaker(speaker));
            }
            if (item instanceof ReflectCatalog.Conversation conversation) {
                return row.conversation(artwork, conversation, progress(conversation.recording.file), () -> {
                    dismissKeyboard();
                    actions.openConversation(conversation);
                });
            }
            if (item instanceof ReflectCatalog.Quote quote) {
                return row.quote(artwork, quote, () -> {
                    dismissKeyboard();
                    actions.openConversationQuote(quote);
                });
            }
            if (item instanceof SearchInside) {
                return row.action(artwork, "Искать в разговорах", () -> {
                    dismissKeyboard();
                    searchInside = true;
                    renderRows(true);
                });
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
            int bottom = emptySpeakers() ? px(536) : getHeight() - px(SPEAKER_BUTTON_BOTTOM_INSET);
            addSpeaker.layout(px(100), bottom - addSpeaker.getMeasuredHeight(), px(295), bottom);
        }
    }

    private void styleSpeakerButton(Button button) {
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * scale);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xddffffff);
        pill.setCornerRadius(px(24));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), pill, null));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scale, scale);
        if (emptySpeakers() || emptyRecords()) {
            artwork.icon(canvas, "invite_cards", 100.81f, 121.1f);
            ReflectLibraryRow.style(paint, 22, 0xfff5f5f5, artwork.display, -.22f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(emptySpeakers() ? "Пока нет спикеров" : "Ваши записи", 197.5f, 419, paint);
            ReflectLibraryRow.style(paint, 16, 0xff999999, artwork.medium, 0);
            paint.setTextAlign(Paint.Align.CENTER);
            if (emptySpeakers()) {
                canvas.drawText("Здесь показываются все", 197.5f, 447, paint);
                canvas.drawText("уникальные спикеры", 197.5f, 468, paint);
            } else {
                canvas.drawText("Здесь будут храниться аудиофайлы", 197.5f, 447, paint);
            }
        } else {
            String title = switch (page) {
                case RECORDS -> "Ваши записи";
                case SPEAKERS -> "Все участники";
                case CONVERSATIONS -> "Ваши разговоры";
            };
            ReflectLibraryRow.text(canvas, paint, title, 16, 27, 24, Color.WHITE, artwork.display, -.24f);
            paint.setColor(0xff333333);
            canvas.drawRoundRect(16, 51, 377, 99, 24, 24, paint);
            artwork.icon(canvas, "search", 28, 63);
        }
        canvas.restore();
    }

    private int px(float design) { return Math.round(design * scale); }
    private int scrollTop() { return 108; }
    private static int exact(int size) { return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY); }
}
