package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Native stage with the design's 393 × 852 coordinate space and Android touch targets. */
final class ReflectView extends ViewGroup {
    enum Screen { HOME, MENU, READY, RECORDING, REVIEW }

    interface Actions {
        void toggleMenu();
        void attach();
        void openRecorder();
        void startRecording();
        void finishRecording();
        void closeRecorder();
        void play();
        void save();
        void send();
        void openRecordings();
    }

    private static final float WIDTH = 393, HEIGHT = 852;
    private static final int BACKGROUND = 0xff040402, WHITE = Color.WHITE;
    private static final int[] LIVE_WAVE = {4,6,10,7,14,9,18,12,8,16,22,13,9,42,52,36,44,20,10,7,12,17,9,5,11,14,8,6,10,7,5,4};
    private static final int[] REVIEW_WAVE = {4,6,10,7,14,9,18,12,8,16,22,13,9,19,11,6,15,20,10,7,12,17,9,5,11,14,8,6,10,7,5,4};
    private static final String[] TAB_ICONS = {"tab_home", "tab_records", "tab_speakers", "tab_settings"};
    private static final String[] TAB_NAMES = {"Главная", "Записи", "Спикеры", "Настройки"};
    private static final float[] TAB_CENTERS = {56, 121.5f, 188.5f, 255.5f};
    private final Actions actions;
    private final ReflectArtwork artwork;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final List<RectF> childAreas = new ArrayList<>();
    private final Path stage = new Path();
    private final Path glass = new Path();
    private final Path playGlyph = new Path();
    private final Bitmap orbEdgeShade = orbEdgeShade();
    private Screen screen = Screen.HOME;
    private Screen departing = Screen.HOME;
    private float scale = 1, offsetX, offsetY, transition = 1, menuProgress;
    private float liveLevel, playProgress;
    private String elapsed = "0:00";
    private String duration = "+ 0:00";
    private boolean playing;
    private ValueAnimator animator;

    ReflectView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        artwork = new ReflectArtwork(context.getAssets());
        setWillNotDraw(false);
        stage.addRoundRect(0, 0, WIDTH, HEIGHT, 47, 47, Path.Direction.CW);
        glass.addRoundRect(175, 661, 377, 749, 26, 26, Path.Direction.CW);
        playGlyph.moveTo(34, 596);
        playGlyph.quadTo(34, 594.6f, 35.2f, 595.3f);
        playGlyph.lineTo(42.5f, 599.2f);
        playGlyph.quadTo(43.8f, 600, 42.5f, 600.8f);
        playGlyph.lineTo(35.2f, 604.7f);
        playGlyph.quadTo(34, 605.4f, 34, 604);
        playGlyph.close();
        rebuildControls();
    }

    Screen screen() { return screen; }

    void show(Screen next) {
        if (next == screen) return;
        if (animator != null) animator.cancel();
        departing = screen;
        screen = next;
        boolean menuOnly = home(departing) && home(next);
        float startMenu = menuProgress;
        float endMenu = next == Screen.MENU ? 1 : 0;
        rebuildControls();
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(menuOnly ? 300 : 340);
        animator.setInterpolator(new PathInterpolator(0.22f, 0.85f, 0.27f, 1f));
        animator.addUpdateListener(value -> {
            float progress = (float) value.getAnimatedValue();
            transition = menuOnly ? 1 : progress;
            menuProgress = startMenu + (endMenu - startMenu) * progress;
            invalidate();
        });
        animator.start();
    }

    void updateRecording(String elapsed, float amplitude) {
        this.elapsed = elapsed;
        liveLevel = 0.72f * liveLevel + 0.28f * amplitude;
        invalidate();
    }

    void updatePlayback(String duration, boolean playing, float progress) {
        this.duration = "+ " + duration;
        this.playing = playing;
        this.playProgress = progress;
        invalidate();
    }

    private static boolean home(Screen page) { return page == Screen.HOME || page == Screen.MENU; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        scale = Math.min(width / WIDTH, height / HEIGHT);
        offsetX = (width - scale * WIDTH) / 2;
        offsetY = (height - scale * HEIGHT) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            RectF bounds = childAreas.get(i);
            View child = getChildAt(i);
            child.measure(
                    MeasureSpec.makeMeasureSpec(Math.round(bounds.width() * scale), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(bounds.height() * scale), MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            View control = getChildAt(i);
            RectF area = childAreas.get(i);
            int x = Math.round(offsetX + area.left * scale), y = Math.round(offsetY + area.top * scale);
            control.layout(x, y, x + control.getMeasuredWidth(), y + control.getMeasuredHeight());
        }
    }

    private void rebuildControls() {
        removeAllViews();
        childAreas.clear();
        setAccessibilityPaneTitle(switch (screen) {
            case HOME -> "Начните работу с Reflect";
            case MENU -> "Добавить аудио";
            case READY -> "Запись аудио";
            case RECORDING -> "Идёт запись";
            case REVIEW -> "Готово";
        });
        if (home(screen)) {
            control(screen == Screen.MENU ? "Закрыть меню" : "Добавить", 318, 765, 59, 59, actions::toggleMenu);
            control("Записи", 89, 771, 65, 47, actions::openRecordings);
            if (screen == Screen.MENU) {
                control("Прикрепить файл", 175, 661, 202, 44, actions::attach);
                control("Записать сейчас", 175, 705, 202, 44, actions::openRecorder);
            }
        } else {
            control("Закрыть", 318, 765, 59, 59, actions::closeRecorder);
            switch (screen) {
                case READY: control("Начать запись", 245, 765, 59, 59, actions::startRecording); break;
                case RECORDING: control("Завершить запись", 245, 765, 59, 59, actions::finishRecording); break;
                case REVIEW:
                    control("Воспроизвести или приостановить", 16, 578, 361, 44, actions::play);
                    control("Расшифровать позже", 84, 75, 222, 48, actions::save);
                    control("Поделиться записью", 245, 765, 59, 59, actions::send);
                    break;
                default: break;
            }
        }
    }

    private void control(String label, float x, float y, float width, float height, Runnable action) {
        View button = new View(getContext()) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.Button");
            }
        };
        button.setContentDescription(label);
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(WHITE);
        mask.setCornerRadius(80);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35aaaaaa), null, mask));
        place(button, x, y, width, height);
    }

    private void place(View child, float x, float y, float width, float height) {
        childAreas.add(new RectF(x, y, x + width, y + height));
        addView(child);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff1e1e1e);
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        rounded(canvas, 0, 0, WIDTH, HEIGHT, 47, BACKGROUND);
        canvas.clipPath(stage);
        if (transition < 1) {
            drawScreen(canvas, departing, departing == Screen.MENU ? 1 : 0);
            canvas.saveLayerAlpha(0, 0, WIDTH, HEIGHT, Math.round(255 * transition));
            canvas.drawColor(BACKGROUND);
            canvas.translate(0, 8 * (1 - transition));
            drawScreen(canvas, screen, menuProgress);
            canvas.restore();
        } else {
            drawScreen(canvas, screen, menuProgress);
        }
        String status = switch (screen) {
            case HOME -> "status_home";
            case RECORDING -> "status_recording";
            case REVIEW -> "status_review";
            default -> "status_menu_ready";
        };
        artwork.icon(canvas, status, 276.1f, 21.05f);
        text(canvas, "9:41", 47.3f, 33.3f, 17.13f, WHITE, artwork.status, false);
        canvas.restore();
    }

    private void drawScreen(Canvas canvas, Screen page, float menu) {
        if (home(page)) {
            drawHome(canvas);
            drawNavigation(canvas, menu, 0);
        }
        else drawRecorder(canvas, page);
    }

    private void drawHome(Canvas c) {
        artwork.image(c, "butterfly", 71, 280, 250, 194);
        text(c, "Начните работу с Reflect", 196, 501, 22, 0xfff5f5f5, artwork.display, true);
        text(c, "Прикрепите файлы или запишите", 186.5f, 533.3f, 16, 0xff999999, artwork.medium, true);
        text(c, "аудио прямо сейчас", 186.5f, 554, 16, 0xff999999, artwork.medium, true);
        artwork.icon(c, "guide_arrow", 177.24f, 579.25f);
    }

    private void drawNavigation(Canvas c, float menu, int selectedTab) {
        rounded(c, 0, 736, 393, 852, 0, (Math.round(3 * (1 - menu)) << 24) | 0xffffff);
        pill(c, 16, 765, 302, 824, 29.5f, 0x1cffffff);
        float center = TAB_CENTERS[selectedTab];
        float halfWidth = selectedTab == 0 ? 34 : 35.5f;
        rounded(c, center - halfWidth, 771, center + halfWidth, 818, 24, 0x1affffff);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            artwork.centeredIcon(c, TAB_ICONS[i], TAB_CENTERS[i], 787);
            text(c, TAB_NAMES[i], TAB_CENTERS[i], 811.3f, 10, WHITE, artwork.regular, true);
        }
        closeButton(c, "plus", -45 * menu);
        if (menu > 0) {
            c.saveLayerAlpha(168, 650, 385, 761, Math.round(menu * 255));
            c.translate(0, 11 * (1 - menu));
            c.scale(0.94f + 0.06f * menu, 0.94f + 0.06f * menu, 363, 749);
            drawMenu(c);
            c.restore();
        }
    }

    private void drawMenu(Canvas c) {
        c.save();
        c.clipPath(glass);
        paint.setShader(new LinearGradient(0, 661, 0, 749,
                new int[]{0xff232323, 0xff272725, 0xff2c2c2a}, null, Shader.TileMode.CLAMP));
        c.drawPath(glass, paint);
        paint.setShader(new RadialGradient(374, 708, 43,
                new int[]{0xff49534c, 0xff423b34, 0x0043413b}, new float[]{0, .46f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(glass, paint);
        paint.setShader(new RadialGradient(336, 660, 33, 0xff3a414d, 0x003a414d, Shader.TileMode.CLAMP));
        c.drawPath(glass, paint);
        paint.setShader(new RadialGradient(232, 663, 38, 0xff373d43, 0x00373d43, Shader.TileMode.CLAMP));
        c.drawPath(glass, paint);
        paint.setShader(null);
        c.restore();
        outline(c, 175, 661, 377, 749, 26, 0xff666765, 0xff525250);
        artwork.icon(c, "attach", 191, 675);
        artwork.icon(c, "mic_menu", 191, 715);
        text(c, "Прикрепить файл", 219.5f, 690, 16, WHITE, artwork.medium, false);
        text(c, "Записать сейчас", 219, 730, 16, WHITE, artwork.medium, false);
    }

    private void drawRecorder(Canvas c, Screen page) {
        if (page == Screen.READY) {
            c.save();
            c.scale(-1, 1, 197, 403.5f);
            artwork.image(c, "orb_ready", 57, 217, 280, 373);
            c.restore();
            c.drawBitmap(orbEdgeShade, 260, 217, null);
            artworkButton(c, WHITE, "mic_start", 274.5f, 794.5f, 0);
        } else {
            c.save();
            c.clipRect(65, 251, 328, 529);
            float pulse = page == Screen.RECORDING ? 1 + .018f * liveLevel : 1;
            c.scale(pulse, pulse, 196.5f, 390);
            artwork.image(c, "orb_active", 39.49f, 251, 289.51f, 278);
            c.restore();
            c.drawBitmap(orbEdgeShade, 260, 217, null);
            if (page == Screen.RECORDING) {
                text(c, "Распознаю голос...", 196.5f, 412.3f, 18, WHITE, artwork.display, true);
                waveform(c, LIVE_WAVE, 45, 349, 627, WHITE, liveLevel, 0);
                text(c, elapsed, 32, 803, 26, WHITE, artwork.display, false);
                artworkButton(c, WHITE, "pause", 274.75f, 794.9f, 0);
            } else {
                text(c, "Готово!", 196.5f, 412.3f, 18, WHITE, artwork.display, true);
                drawReview(c);
                artworkButton(c, 0xff0088ff, "send", 275.26f, 794.75f, 90);
            }
        }
        closeButton(c, "plus_recorder", -45);
    }

    private void drawReview(Canvas c) {
        rounded(c, 84, 75, 306, 123, 24, WHITE);
        text(c, "Расшифровать позже", 195, 104.8f, 16, BACKGROUND, artwork.medium, true);
        rounded(c, 16, 578, 377, 622, 22, 0xff090908);
        outline(c, 16, 578, 377, 622, 22, 0xff696968, 0xff3c3c39);
        rounded(c, 23, 585, 53, 615, 15, 0x1f767680);
        paint.setColor(WHITE);
        if (playing) {
            rounded(c, 34, 595, 36.5f, 605, 1, WHITE);
            rounded(c, 39, 595, 41.5f, 605, 1, WHITE);
        } else c.drawPath(playGlyph, paint);
        waveform(c, REVIEW_WAVE, 63, 306, 600, 0xff999999, 0, playProgress);
        rounded(c, 316, 588, 370, 612, 12, 0x1f767680);
        text(c, duration, 343, 604.7f, 13, WHITE, artwork.duration, true);
        text(c, "Отправьте аудио и получите готовую", 196.5f, 657.3f, 16, 0xff999999, artwork.medium, true);
        text(c, "расшифровку по спикерам", 196.5f, 678.3f, 16, 0xff999999, artwork.medium, true);
    }

    private void waveform(Canvas c, int[] bars, float left, float right, float center, int color,
                          float level, float progress) {
        for (int i = 0; i < bars.length; i++) {
            float fraction = (float) i / (bars.length - 1);
            float x = left + fraction * (right - left - 2);
            float h = bars[i] * (1 + level * .22f);
            rounded(c, x, center - h / 2, x + 2, center + h / 2, 1,
                    fraction < progress ? WHITE : color);
        }
    }

    private void closeButton(Canvas c, String asset, float angle) {
        pill(c, 318, 765, 377, 824, 29.5f, 0x1cffffff);
        rotatedIcon(c, asset, 347.5f, 794.5f, angle);
    }

    private void artworkButton(Canvas c, int fill, String asset, float x, float y, float angle) {
        rounded(c, 245, 765, 304, 824, 29.5f, fill);
        rotatedIcon(c, asset, x, y, angle);
    }

    private void rotatedIcon(Canvas c, String name, float x, float y, float angle) {
        c.save();
        c.rotate(angle, x, y);
        artwork.centeredIcon(c, name, x, y);
        c.restore();
    }

    private void pill(Canvas c, float x, float y, float right, float bottom, float radius, int color) {
        paint.setColor(WHITE);
        paint.setShader(new LinearGradient(x, y, x, bottom, 0x19ffffff, color, Shader.TileMode.CLAMP));
        c.drawRoundRect(x, y, right, bottom, radius, radius, paint);
        paint.setShader(null);
        outline(c, x, y, right, bottom, radius, 0x25ffffff, 0x0cffffff);
    }

    private static Bitmap orbEdgeShade() {
        Bitmap mask = Bitmap.createBitmap(153, 405, Bitmap.Config.ARGB_8888);
        mask.setDensity(Bitmap.DENSITY_NONE);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setColor(BACKGROUND);
        shade.setMaskFilter(new BlurMaskFilter(34, BlurMaskFilter.Blur.NORMAL));
        new Canvas(mask).drawRect(65, 65, 88, 340, shade);
        return mask;
    }

    private void outline(Canvas c, float x, float y, float right, float bottom, float radius, int top, int end) {
        paint.setColor(WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.7f);
        paint.setShader(new LinearGradient(x, y, right, bottom, top, end, Shader.TileMode.CLAMP));
        c.drawRoundRect(x + .35f, y + .35f, right - .35f, bottom - .35f, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private void rounded(Canvas c, float x, float y, float right, float bottom, float radius, int color) {
        paint.setColor(color);
        c.drawRoundRect(x, y, right, bottom, radius, radius, paint);
    }

    private void text(Canvas c, String text, float x, float baseline, float size, int color, Typeface typeface,
                      boolean center) {
        paint.setColor(color);
        paint.setTypeface(typeface);
        paint.setTextSize(size);
        paint.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
        float tracking = typeface == artwork.display ? -size * .01f
                : typeface == artwork.medium ? (center ? -.16f : -.32f) : 0;
        paint.setLetterSpacing(tracking / size);
        c.drawText(text, x, baseline, paint);
    }
}
