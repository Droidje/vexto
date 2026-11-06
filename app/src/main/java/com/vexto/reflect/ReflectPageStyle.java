package com.vexto.reflect;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The typography and spacing of the Reflect stage, shared by native detail views. */
final class ReflectPageStyle {
    static final int MUTED = 0xffa4a4a4;
    static final int CARD = 0xff242424;
    static final int ACCENT = 0xff0088ff;
    final ReflectArtwork art;
    private final Context context;
    private final float scale;

    ReflectPageStyle(Context context, ReflectArtwork art, int width) {
        this.context = context;
        this.art = art;
        scale = width / 393f;
    }

    int px(float design) { return Math.round(design * scale); }

    TextView text(CharSequence text, float size, int color, Typeface font) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale);
        view.setTypeface(font);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return view;
    }

    TextView title(String value) {
        TextView title = text(value, 24, Color.WHITE, art.display);
        title.setLetterSpacing(-.01f);
        title.setAccessibilityHeading(true);
        return title;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(px(radius));
        return background;
    }

    Drawable pressable(int color, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x24ffffff),
                rounded(color, radius), null);
    }

    void append(LinearLayout column, android.view.View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = px(top);
        params.bottomMargin = px(bottom);
        column.addView(view, params);
    }
}
