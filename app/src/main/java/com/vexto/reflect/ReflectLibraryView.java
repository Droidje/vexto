package com.vexto.reflect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Content above the shared navigation. Geometry follows the 393 dp design stage. */
final class ReflectLibraryView extends ViewGroup {
    private final ReflectArtwork artwork;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final LinearLayout rows;
    private final ScrollView scroller;
    private final EditText search;
    private final Consumer<ReflectCatalog.Recording> openRecording;
    private List<ReflectCatalog.Recording> recordings = Collections.emptyList();
    private float scale = 1;

    ReflectLibraryView(Context context, ReflectArtwork artwork, Consumer<ReflectCatalog.Recording> openRecording) {
        super(context);
        this.artwork = artwork;
        this.openRecording = openRecording;
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
        search.setHint("Поиск по участникам, тегам");
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
        requestFocus();
    }

    void setRecordings(List<ReflectCatalog.Recording> recordings) {
        this.recordings = recordings;
        renderRows(false);
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
        String section = null;
        for (ReflectCatalog.Recording recording : recordings) {
            if (!ReflectCatalog.matches(recording.search, query)) continue;
            if (!recording.date.equals(section)) {
                section = recording.date;
                rows.addView(ReflectLibraryRow.heading(getContext(), artwork, section));
            }
            rows.addView(ReflectLibraryRow.recording(getContext(), artwork, recording,
                    () -> { dismissKeyboard(); openRecording.accept(recording); }));
        }
        if (rows.getChildCount() == 0) {
            String message = query.trim().isEmpty() ? "Сохранённых записей пока нет" : "Ничего не найдено";
            rows.addView(ReflectLibraryRow.message(getContext(), artwork, message));
        }
        if (scrollToTop) scroller.scrollTo(0, 0);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        scale = width / 393f;
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16 * scale);
        search.setPadding(px(48), 0, px(12), 0);
        search.measure(exact(px(361)), exact(px(48)));
        scroller.measure(exact(width), exact(Math.max(0, height - px(125))));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        search.layout(px(16), px(51), px(377), px(99));
        scroller.layout(0, px(125), getWidth(), getHeight());
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scale, scale);
        ReflectLibraryRow.text(canvas, paint, "Ваши записи", 16, 27, 24, Color.WHITE, artwork.display, -.24f);
        paint.setColor(0xff333333);
        canvas.drawRoundRect(16, 51, 377, 99, 24, 24, paint);
        artwork.icon(canvas, "search", 28, 63);
        canvas.restore();
    }

    private int px(float design) { return Math.round(design * scale); }
    private static int exact(int size) { return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY); }
}
