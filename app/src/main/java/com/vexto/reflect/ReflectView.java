package com.vexto.reflect;

import android.animation.ValueAnimator;
import android.app.Activity;
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
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Native stage with the design's 393 × 852 coordinate space and Android touch targets. */
final class ReflectView extends ViewGroup {
    enum Screen { HOME, RECORDS, SPEAKERS, SETTINGS, MENU, READY, RECORDING, PAUSED, REVIEW, PROCESSING, TRANSCRIPT, SPEAKER_PROFILE, EXPORT }

    interface Actions extends ReflectLibraryView.Actions, ReflectTranscriptView.Actions, ReflectExportView.Actions {
        void toggleMenu();
        void attach();
        void openRecorder();
        void openCallRecorder();
        void quickRecord();
        void startRecording();
        void pauseRecording();
        void finishRecording();
        void closeRecorder();
        void play();
        void save();
        void transcribeReview();
        void openMicrophoneSettings();
        void refreshSpeakers();
        void closeSpeakerProfile();
        void openSpeakerMenu();
        void renameSpeakerProfile();
        void openSpeakerUtterance(SpeakerDirectory.Utterance utterance);
    }

    private static final float WIDTH = 393, HEIGHT = 852;
    static final int NAV_BOTTOM_CLEARANCE = 99;
    private static final float LIBRARY_TOP = 83;
    private static final float FINISH_LEFT = 172, FINISH_CENTER_X = 201.5f;
    private static final int BACKGROUND = 0xff040402, WHITE = Color.WHITE;
    private static final Screen[] TABS = {Screen.HOME, Screen.RECORDS, Screen.SPEAKERS, Screen.SETTINGS};
    private static final String[] TAB_ICONS = {"tab_home", "tab_records", "tab_speakers", "tab_settings"};
    private static final String[] TAB_NAMES = {"Главная", "Записи", "Спикеры", "Настройки"};
    private static final float TAB_LEFT = 22, TAB_WIDTH = 68.5f;
    private static final float TAB_RIGHT = TAB_LEFT + TAB_WIDTH * TAB_NAMES.length;
    private static final LinearInterpolator LINEAR = new LinearInterpolator();
    private static final float CAPSULE_HALF_WIDTH = TAB_WIDTH / 2;
    private static final Shader PILL_FILL = edge(0, 765, 0, 824, 0x19ffffff, 0x1cffffff);
    private static final Shader NAV_FILL = edge(0, 765, 0, 824, 0xb32a2a2a, 0xb3303030);
    private static final Shader NAV_EDGE = edge(16, 765, 302, 824, 0x25ffffff, 0x0cffffff);
    private static final Shader PLUS_EDGE = edge(318, 765, 377, 824, 0x25ffffff, 0x0cffffff);
    private static final Shader MENU_EDGE = edge(175, 661, 377, 749, 0xff666765, 0xff525250);
    private static final Shader[] MENU_FILL = {
            new LinearGradient(0, 661, 0, 749,
                    new int[]{0xc2232323, 0xc2272725, 0xc22c2c2a}, null, Shader.TileMode.CLAMP),
            new RadialGradient(374, 708, 43,
                    new int[]{0x8849534c, 0x66423b34, 0x0043413b}, new float[]{0, .46f, 1}, Shader.TileMode.CLAMP),
            new RadialGradient(336, 660, 33, 0x883a414d, 0x003a414d, Shader.TileMode.CLAMP),
            new RadialGradient(232, 663, 38, 0x88373d43, 0x00373d43, Shader.TileMode.CLAMP)
    };
    private final Actions actions;
    private final String welcomeTitle;
    private final ReflectArtwork artwork;
    private final ReflectPreferences preferences;
    private final ReflectSettingsView settings;
    private final ReflectTranscriptView transcript;
    private final SpeakerProfileView speakerProfile;
    private final ReflectProcessingView processing;
    private final ReflectExportView export;
    private final ReflectLibraryView records, speakers, conversations;
    private final View navigationGlass;
    private final ReflectGlassBackdrop navigationBackdrop = new ReflectGlassBackdrop();
    private final ReflectGlassBackdrop plusBackdrop = new ReflectGlassBackdrop();
    private final ReflectGlassBackdrop menuBackdrop = new ReflectGlassBackdrop();
    private final ReflectGlassBackdrop.Source navigationSource = this::drawPageContents;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint snapshotPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF snapshotBounds = new RectF(0, 0, WIDTH, HEIGHT);
    private final List<RectF> childAreas = new ArrayList<>();
    private final Path stage = new Path();
    private final Path glass = new Path();
    private final RecordingVisuals recordingVisuals = new RecordingVisuals();
    private RecordingOrb recordingOrb;
    private Bitmap orbEdgeShade;
    private Screen screen = Screen.HOME;
    private Screen tab = Screen.HOME;
    private float scale = 1, offsetX, offsetY, pageProgress = 1, menuProgress;
    private float capsuleLeft = TAB_LEFT, capsuleRight = TAB_LEFT + TAB_WIDTH;
    private int pageDirection = 1, pageSteps = 1;
    private Screen departingScreen;
    private Bitmap departingPage;
    private boolean callMode;

    void callMode(boolean value) {
        callMode = value;
        invalidate();
    }

    private Bitmap[] passingTabs;
    private View pageContent;
    private ReflectConversationPlayer reviewPlayer;
    private int reviewPosition, reviewDuration;
    private int[] reviewWave;
    private long elapsedSeconds;
    private String elapsed = "0:00";
    private String reviewLength = "0:00";
    private boolean reviewPlaying;
    private ValueAnimator pageAnimator, navigationAnimator;

    ReflectView(Context context, Actions actions, ReflectPreferences preferences) {
        super(context);
        this.actions = actions;
        welcomeTitle = context.getString(R.string.welcome_title, context.getString(R.string.app_name));
        this.preferences = preferences;
        artwork = new ReflectArtwork(context.getAssets());
        settings = new ReflectSettingsView(context, artwork, preferences, actions::openMicrophoneSettings);
        transcript = new ReflectTranscriptView(context, artwork, preferences, actions);
        speakerProfile = new SpeakerProfileView(context, artwork, preferences, actions::closeSpeakerProfile,
                actions::openSpeakerMenu, actions::renameSpeakerProfile, actions::openSpeakerUtterance);
        speakerProfile.setBackgroundColor(BACKGROUND);
        processing = new ReflectProcessingView(context, artwork, preferences, actions::closeTranscript);
        export = new ReflectExportView(context, artwork, preferences, actions);
        records = new ReflectLibraryView(context, artwork, preferences, ReflectLibraryView.Page.RECORDS, actions);
        speakers = new ReflectLibraryView(context, artwork, preferences, ReflectLibraryView.Page.SPEAKERS, actions);
        conversations = new ReflectLibraryView(context, artwork, preferences, ReflectLibraryView.Page.CONVERSATIONS, actions);
        navigationGlass = new View(context) {
            @Override protected void onDraw(Canvas canvas) {
                canvas.save();
                canvas.scale(scale, scale);
                drawNavigationOverlay(canvas);
                canvas.restore();
            }
        };
        navigationGlass.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        stage.addRoundRect(0, 0, WIDTH, HEIGHT, 47, 47, Path.Direction.CW);
        glass.addRoundRect(175, 617, 377, 749, 26, 26, Path.Direction.CW);
        rebuildControls();
    }

    Screen screen() { return screen; }
    Screen tab() { return tab; }
    ReflectTranscriptView transcriptView() { return transcript; }
    SpeakerProfileView speakerProfileView() { return speakerProfile; }
    ReflectProcessingView processingView() { return processing; }
    ReflectExportView exportView() { return export; }
    void trimMemory(int level) { artwork.trimMemory(level); }
    ReflectPageStyle pageStyle() { return new ReflectPageStyle(getContext(), artwork, Math.max(1, Math.round(WIDTH * scale))); }
    ReflectActionSheet actionSheet(Activity activity, String title) {
        return new ReflectActionSheet(activity, pageStyle(), preferences, title);
    }

    void refreshSettings() { settings.refresh(); }
    void setRecordings(List<ReflectCatalog.Recording> recordings) { records.setRecordings(recordings); }
    void setSpeakers(List<ReflectCatalog.Speaker> entries) { speakers.setSpeakers(entries); }
    void updateLibraryProgress(File recording, ReflectLocalTranscription.Progress progress) {
        records.updateProgress(recording, progress);
        conversations.updateProgress(recording, progress);
    }

    void setConversations(List<ReflectCatalog.Conversation> entries) {
        boolean hadConversations = conversations.hasConversations();
        conversations.setConversations(entries);
        if (navigation(screen) && tab == Screen.HOME && hadConversations != conversations.hasConversations()) {
            rebuildControls();
            invalidate();
        }
    }

    void show(Screen next) {
        if (next == screen) return;
        Screen previous = screen;
        Screen targetTab = isTab(next) ? next : tab;
        boolean withinNavigation = navigation(previous) && navigation(next);
        boolean withinRecorder = recordingScreen(previous) && recordingScreen(next);
        boolean pageChanged = !withinRecorder && (!withinNavigation || tab != targetTab);
        boolean motion = preferences.motionEnabled();
        boolean animate = motion && isLaidOut() && scale > 0;
        if (pageChanged) {
            if (departingPage != null) {
                finishPageTransition();
                applyPagePosition();
            }
            Bitmap snapshot = animate ? capturePage() : null;
            pageDirection = withinNavigation ? Integer.compare(tabIndex(targetTab), tabIndex(tab))
                    : returnTransition(previous, next) ? -1 : 1;
            departingPage = snapshot;
            pageSteps = snapshot != null && withinNavigation ? Math.abs(tabIndex(targetTab) - tabIndex(tab)) : 1;
            if (pageSteps > 1) {
                passingTabs = new Bitmap[pageSteps - 1];
                for (int i = 0; i < passingTabs.length; i++) {
                    passingTabs[i] = captureTab(TABS[tabIndex(tab) + pageDirection * (i + 1)]);
                }
            }
            departingScreen = snapshot == null ? null : previous;
            pageProgress = snapshot == null ? 1 : 0;
        } else if (!animate) {
            finishPageTransition();
        }
        if (tab == Screen.HOME && navigation(previous)) conversations.dismissKeyboard();
        if (tab == Screen.RECORDS && navigation(previous)) records.dismissKeyboard();
        if (tab == Screen.SPEAKERS && navigation(previous)) speakers.dismissKeyboard();
        long now = SystemClock.uptimeMillis();
        if (next == Screen.RECORDING) recordingVisuals.start(now, motion);
        else if (next == Screen.PAUSED) recordingVisuals.pause(now);
        else if (previous == Screen.RECORDING || previous == Screen.PAUSED) recordingVisuals.reset();
        tab = targetTab;
        screen = next;
        rebuildControls();
        applyPagePosition();
        navigationGlass.invalidate();
        invalidate();
        if (pageChanged && departingPage != null) startPageTransition();
        if (!withinRecorder) animateNavigation(animate);
    }

    private static boolean returnTransition(Screen previous, Screen next) {
        return navigation(next) || previous == Screen.EXPORT
                || (previous == Screen.TRANSCRIPT && next == Screen.SPEAKER_PROFILE)
                || (previous == Screen.REVIEW && recordingScreen(next));
    }

    private void animateNavigation(boolean animate) {
        if (navigationAnimator != null) navigationAnimator.cancel();
        float startMenu = menuProgress;
        float endMenu = screen == Screen.MENU ? 1 : 0;
        float startLeft = capsuleLeft, startRight = capsuleRight;
        float targetLeft = TAB_LEFT + TAB_WIDTH * tabIndex(tab);
        float targetRight = targetLeft + TAB_WIDTH;
        boolean movingRight = targetLeft > startLeft;
        if (!animate || (startMenu == endMenu && startLeft == targetLeft && startRight == targetRight)) {
            menuProgress = endMenu;
            capsuleLeft = targetLeft;
            capsuleRight = targetRight;
            navigationGlass.invalidate();
            invalidate();
            return;
        }
        navigationAnimator = ValueAnimator.ofFloat(0, 1);
        boolean skippingTabs = pageSteps > 1;
        boolean movingCapsule = startLeft != targetLeft || startRight != targetRight;
        navigationAnimator.setDuration(movingCapsule ? pageDuration() : 300);
        navigationAnimator.addUpdateListener(value -> {
            float time = (float) value.getAnimatedValue();
            float eased = ReflectMotion.EASE.getInterpolation(time);
            float leading = ReflectMotion.EASE.getInterpolation(Math.min(1, time * 1.18f));
            float capsuleProgress = skippingTabs ? ReflectMotion.TAB_STRIP.getInterpolation(time) : eased;
            if (skippingTabs) leading = capsuleProgress;
            menuProgress = lerp(startMenu, endMenu, eased);
            capsuleLeft = lerp(startLeft, targetLeft, movingRight ? capsuleProgress : leading);
            capsuleRight = lerp(startRight, targetRight, movingRight ? leading : capsuleProgress);
            navigationGlass.invalidate();
            invalidate();
        });
        navigationAnimator.setInterpolator(LINEAR);
        navigationAnimator.start();
    }

    private int pageDuration() {
        return switch (pageSteps) {
            case 2 -> 235;
            case 3 -> 255;
            default -> 210;
        };
    }

    private void startPageTransition() {
        pageAnimator = ValueAnimator.ofFloat(0, 1);
        pageAnimator.setDuration(pageDuration());
        pageAnimator.setInterpolator(pageSteps > 1 ? ReflectMotion.TAB_STRIP : ReflectMotion.PAGE);
        pageAnimator.addUpdateListener(value -> {
            pageProgress = (float) value.getAnimatedValue();
            if (pageProgress >= 1) finishPageTransition();
            applyPagePosition();
            navigationGlass.invalidate();
            invalidate();
        });
        pageAnimator.start();
    }

    private void finishPageTransition() {
        if (pageAnimator != null && pageAnimator.isRunning() && pageProgress < 1) pageAnimator.cancel();
        departingPage = null;
        passingTabs = null;
        pageSteps = 1;
        departingScreen = null;
        pageProgress = 1;
    }

    private float incomingOffset() { return pageOffset(pageSteps); }

    private float pageOffset(int index) { return pageDirection * WIDTH * (index - pageSteps * pageProgress); }

    private boolean isPageChild(View child) { return !navigation(screen) || child == pageContent; }

    private void applyPagePosition() {
        float translation = incomingOffset() * scale;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (isPageChild(child)) child.setTranslationX(translation);
        }
    }

    private static int tabIndex(Screen tab) {
        return switch (tab) {
            case RECORDS -> 1;
            case SPEAKERS -> 2;
            case SETTINGS -> 3;
            default -> 0;
        };
    }

    private static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    void updateRecording(long elapsedMillis, int peak) {
        long seconds = elapsedMillis / 1000;
        if (seconds != elapsedSeconds) {
            elapsedSeconds = seconds;
            long remainder = seconds % 60;
            elapsed = seconds / 60 + ":" + (remainder < 10 ? "0" : "") + remainder;
        }
        if (screen == Screen.RECORDING) recordingVisuals.sample(peak, SystemClock.uptimeMillis());
        invalidate();
    }

    void setReviewWave(int[] bars) {
        reviewWave = bars;
        if (reviewPlayer != null) reviewPlayer.waveform(bars);
    }

    void updateReviewPlayback(String length, boolean playing, int position, int duration) {
        reviewLength = length;
        reviewPlaying = playing;
        reviewPosition = position;
        reviewDuration = duration;
        if (reviewPlayer != null) reviewPlayer.update(playing, position, duration);
    }

    static boolean isTab(Screen page) {
        return page == Screen.HOME || page == Screen.RECORDS || page == Screen.SPEAKERS || page == Screen.SETTINGS;
    }

    private static boolean navigation(Screen page) { return isTab(page) || page == Screen.MENU; }

    private static boolean recordingScreen(Screen page) {
        return page == Screen.READY || page == Screen.RECORDING || page == Screen.PAUSED;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        scale = Math.min(width / WIDTH, height / HEIGHT);
        artwork.setScale(scale);
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
        applyPagePosition();
    }

    private void rebuildControls() {
        removeAllViews();
        childAreas.clear();
        reviewPlayer = null;
        if (pageContent != null) {
            pageContent.setAlpha(1);
            pageContent.setTranslationX(0);
            pageContent.setTranslationY(0);
            pageContent = null;
        }
        setAccessibilityPaneTitle(switch (screen) {
            case HOME -> conversations.hasConversations() ? "Ваши разговоры" : welcomeTitle;
            case RECORDS -> "Ваши записи";
            case SPEAKERS -> "Спикеры";
            case SETTINGS -> "Настройки";
            case MENU -> "Добавить аудио";
            case READY -> callMode ? "Запись звонка" : "Запись аудио";
            case RECORDING -> "Идёт запись";
            case PAUSED -> "Запись на паузе";
            case REVIEW -> "Готово";
            case PROCESSING -> "Локальная расшифровка";
            case TRANSCRIPT -> "Транскрипт разговора";
            case SPEAKER_PROFILE -> "Профиль спикера";
            case EXPORT -> "Экспорт расшифровки";
        });
        if (screen == Screen.TRANSCRIPT) {
            placePage(transcript, 0, WIDTH, HEIGHT);
        } else if (screen == Screen.SPEAKER_PROFILE) {
            placePage(speakerProfile, 0, WIDTH, HEIGHT);
        } else if (screen == Screen.PROCESSING) {
            placePage(processing, 0, WIDTH, HEIGHT);
        } else if (screen == Screen.EXPORT) {
            placePage(export, 0, WIDTH, HEIGHT);
        } else if (navigation(screen)) {
            View content = tabContent(tab);
            if (content != null) placePage(content, LIBRARY_TOP, WIDTH, HEIGHT - LIBRARY_TOP);
            place(navigationGlass, 0, 0, WIDTH, HEIGHT);
            if (screen == Screen.MENU) {
                View outside = new View(getContext());
                outside.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                outside.setOnClickListener(view -> actions.toggleMenu());
                place(outside, 0, 0, WIDTH, HEIGHT);
            }
            View add = control(screen == Screen.MENU ? "Закрыть меню" : "Добавить", 318, 765, 59, 59,
                    actions::toggleMenu);
            if (screen == Screen.HOME) add.setOnLongClickListener(view -> {
                actions.quickRecord();
                return true;
            });
            tabControl(Screen.HOME, () -> show(Screen.HOME));
            tabControl(Screen.RECORDS, () -> show(Screen.RECORDS));
            tabControl(Screen.SPEAKERS, () -> {
                show(Screen.SPEAKERS);
                actions.refreshSpeakers();
            });
            tabControl(Screen.SETTINGS, () -> show(Screen.SETTINGS));
            if (screen == Screen.MENU) {
                control("Прикрепить файл", 175, 617, 202, 44, actions::attach);
                control("Записать сейчас", 175, 661, 202, 44, actions::openRecorder);
                control("Звонок", 175, 705, 202, 44, actions::openCallRecorder);
            }
        } else {
            if (screen == Screen.RECORDING || screen == Screen.PAUSED) {
                control("Завершить запись", FINISH_LEFT, 765, 59, 59, actions::finishRecording);
            }
            control("Закрыть", 318, 765, 59, 59, actions::closeRecorder);
            switch (screen) {
                case READY -> recordingControls("Начать запись", actions::startRecording);
                case RECORDING -> recordingControls("Поставить запись на паузу", actions::pauseRecording);
                case PAUSED -> recordingControls("Продолжить запись", actions::startRecording);
                case REVIEW -> {
                    reviewPlayer = new ReflectConversationPlayer(getContext(), artwork, preferences, reviewLength,
                            "запись", actions::play, actions::seekPlayback);
                    reviewPlayer.waveform(reviewWave);
                    reviewPlayer.update(reviewPlaying, reviewPosition, reviewDuration);
                    place(reviewPlayer, 16, 578, 361, 44);
                    control("Расшифровать в фоне", 84, 75, 222, 48, actions::save);
                    control("Расшифровать сейчас", 245, 765, 59, 59, actions::transcribeReview);
                }
                default -> { }
            }
        }
    }

    private void recordingControls(String label, Runnable action) {
        control(label, 245, 765, 59, 59, action);
        float radius = RecordingOrb.RADIUS;
        control(label + " (сфера)", RecordingOrb.CENTER_X - radius, RecordingOrb.CENTER_Y - radius,
                2 * radius, 2 * radius, action, true);
    }

    private void tabControl(Screen target, Runnable action) {
        int index = tabIndex(target);
        control(TAB_NAMES[index], TAB_LEFT + index * TAB_WIDTH, 771, TAB_WIDTH, 47, action)
                .setSelected(tab == target);
    }

    private void placePage(View child, float y, float width, float height) {
        pageContent = child;
        place(child, 0, y, width, height);
    }

    private View control(String label, float x, float y, float width, float height, Runnable action) {
        return control(label, x, y, width, height, action, false);
    }

    private View control(String label, float x, float y, float width, float height,
                         Runnable action, boolean circular) {
        View button = new View(getContext()) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (circular && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    float dx = 2 * event.getX() / getWidth() - 1;
                    float dy = 2 * event.getY() / getHeight() - 1;
                    if (dx * dx + dy * dy > 1) return false;
                }
                return super.onTouchEvent(event);
            }

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
        if (circular) mask.setShape(GradientDrawable.OVAL);
        else mask.setCornerRadius(80);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35aaaaaa), null, mask));
        place(button, x, y, width, height);
        return button;
    }

    private void place(View child, float x, float y, float width, float height) {
        childAreas.add(new RectF(x, y, x + width, y + height));
        addView(child);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff1e1e1e);
        int save = canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        rounded(canvas, 0, 0, WIDTH, HEIGHT, 47, BACKGROUND);
        canvas.clipPath(stage);
        drawPageCanvas(canvas);
        canvas.restoreToCount(save);
        if (recordingScreen(screen) && recordingVisuals.needsFrame(SystemClock.uptimeMillis())) {
            postInvalidateOnAnimation();
        }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (scale <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        int save = canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        canvas.clipPath(stage);
        int local = canvas.save();
        canvas.scale(1 / scale, 1 / scale);
        canvas.translate(-offsetX, -offsetY);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(local);
        if (!navigation(screen) && departingScreen != null && navigation(departingScreen)) {
            drawNavigationOverlay(canvas);
        }
        canvas.restoreToCount(save);
    }

    @Override public void onDescendantInvalidated(View child, View target) {
        super.onDescendantInvalidated(child, target);
        if (navigationGlass != null && navigation(screen) && child == pageContent) {
            navigationGlass.invalidate();
        }
    }

    private View tabContent(Screen page) {
        return switch (page) {
            case HOME -> conversations.hasConversations() ? conversations : null;
            case RECORDS -> records;
            case SPEAKERS -> speakers;
            case SETTINGS -> settings;
            default -> throw new IllegalArgumentException("Not a tab: " + page);
        };
    }

    private Bitmap capturePage() {
        Bitmap snapshot = newPageSnapshot();
        drawPageContents(snapshotCanvas(snapshot));
        return snapshot;
    }

    private Bitmap captureTab(Screen page) {
        // Intermediate tabs are painted once without attaching them or changing navigation state.
        Bitmap snapshot = newPageSnapshot();
        Canvas canvas = snapshotCanvas(snapshot);
        View content = tabContent(page);
        if (content == null) {
            drawHome(canvas);
        } else {
            int width = Math.round(WIDTH * scale), height = Math.round((HEIGHT - LIBRARY_TOP) * scale);
            content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            canvas.translate(0, LIBRARY_TOP);
            canvas.scale(1 / scale, 1 / scale);
            canvas.clipRect(0, 0, width, height);
            canvas.translate(-content.getScrollX(), -content.getScrollY());
            content.draw(canvas);
        }
        return snapshot;
    }

    private Bitmap newPageSnapshot() {
        float resolution = Math.min(1, scale);
        int width = Math.max(1, Math.round(WIDTH * resolution));
        int height = Math.max(1, Math.round(HEIGHT * resolution));
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private Canvas snapshotCanvas(Bitmap snapshot) {
        Canvas canvas = new Canvas(snapshot);
        canvas.drawColor(BACKGROUND);
        canvas.scale(snapshot.getWidth() / WIDTH, snapshot.getHeight() / HEIGHT);
        return canvas;
    }

    private void drawPageCanvas(Canvas canvas) {
        if (departingPage != null) drawPageSnapshot(canvas, departingPage, 0);
        if (passingTabs != null) {
            for (int i = 0; i < passingTabs.length; i++) drawPageSnapshot(canvas, passingTabs[i], i + 1);
        }
        int save = canvas.save();
        canvas.translate(incomingOffset(), 0);
        drawScreen(canvas, screen);
        canvas.restoreToCount(save);
    }

    private void drawPageSnapshot(Canvas canvas, Bitmap snapshot, int index) {
        float left = pageOffset(index);
        if (left <= -WIDTH || left >= WIDTH) return;
        int save = canvas.save();
        canvas.translate(left, 0);
        canvas.drawBitmap(snapshot, null, snapshotBounds, snapshotPaint);
        canvas.restoreToCount(save);
    }

    private void drawPageContents(Canvas canvas) {
        drawPageCanvas(canvas);
        int save = canvas.save();
        canvas.translate(-offsetX / scale, -offsetY / scale);
        canvas.scale(1 / scale, 1 / scale);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (isPageChild(child)) drawChild(canvas, child, getDrawingTime());
        }
        canvas.restoreToCount(save);
    }

    private void drawNavigationOverlay(Canvas canvas) {
        boolean crossedNavigation = departingScreen != null
                && navigation(departingScreen) != navigation(screen);
        if (!crossedNavigation) {
            drawNavigation(canvas, menuProgress);
            return;
        }
        float visibility = navigation(screen) ? pageProgress : 1 - pageProgress;
        if (visibility <= 0) return;
        int save = canvas.saveLayerAlpha(0, 649, WIDTH, 825, Math.round(visibility * 255));
        drawNavigation(canvas, menuProgress);
        canvas.restoreToCount(save);
    }

    @Override protected void onDetachedFromWindow() {
        finishPageTransition();
        if (navigationAnimator != null) navigationAnimator.end();
        applyPagePosition();
        super.onDetachedFromWindow();
    }

    private void drawScreen(Canvas canvas, Screen page) {
        if (navigation(page)) {
            Screen active = page == Screen.MENU ? tab : page;
            if (active == Screen.HOME && !conversations.hasConversations()) drawHome(canvas);
        }
        else if (page != Screen.TRANSCRIPT && page != Screen.PROCESSING && page != Screen.SPEAKER_PROFILE
                && page != Screen.EXPORT) {
            drawRecorder(canvas, page);
        }
    }

    private void drawHome(Canvas c) {
        artwork.image(c, "butterfly", 71, 280, 250, 194);
        text(c, welcomeTitle, 196, 501, 22, 0xfff5f5f5, artwork.display, true);
        text(c, "Прикрепите файлы или запишите", 186.5f, 533.3f, 16, 0xff999999, artwork.medium, true);
        text(c, "аудио прямо сейчас", 186.5f, 554, 16, 0xff999999, artwork.medium, true);
        artwork.icon(c, "guide_arrow", 177.24f, 579.25f);
    }

    private void drawNavigation(Canvas c, float menu) {
        drawNavigationBar(c, menu, true);
        if (menu > 0) {
            int save = c.saveLayerAlpha(166, 605, 389, 769, Math.round(menu * 255));
            float shift = 14 * (1 - menu);
            float stretchX = .9f + .1f * menu;
            float stretchY = .86f + .14f * menu;
            c.translate(0, shift);
            c.scale(stretchX, stretchY, 359, 744);
            menuBackdrop.draw(c, 175, 617, 377, 749, 26, 13, sample -> {
                // Undo the menu transform to sample the scene in stage coordinates.
                sample.scale(1 / stretchX, 1 / stretchY, 359, 744);
                sample.translate(0, -shift);
                drawPageContents(sample);
                drawNavigationBar(sample, menu, false);
            });
            drawMenu(c);
            c.restoreToCount(save);
        }
    }

    private void drawNavigationBar(Canvas c, float menu, boolean capture) {
        drawNavigationBackdrop(c, navigationBackdrop, 16, 302, capture);
        pill(c, 16, 765, 302, 824, NAV_FILL, NAV_EDGE);
        rounded(c, Math.max(TAB_LEFT, capsuleLeft), 771, Math.min(TAB_RIGHT, capsuleRight),
                818, 24, 0x24ffffff);
        float center = (capsuleLeft + capsuleRight) / 2;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            float x = TAB_LEFT + i * TAB_WIDTH + CAPSULE_HALF_WIDTH;
            float emphasis = Math.max(0, 1 - Math.abs(center - x) / TAB_WIDTH);
            float iconScale = .96f + .06f * emphasis;
            c.save();
            c.scale(iconScale, iconScale, x, 787);
            artwork.centeredIcon(c, TAB_ICONS[i], x, 787 - emphasis * .6f);
            c.restore();
            text(c, TAB_NAMES[i], x, 811.3f, 10, WHITE, artwork.regular, true);
        }
        drawNavigationBackdrop(c, plusBackdrop, 318, 377, capture);
        pill(c, 318, 765, 377, 824, NAV_FILL, PLUS_EDGE);
        rotatedIcon(c, "plus", 347.5f, 794.5f, -45 * menu);
    }

    private void drawNavigationBackdrop(Canvas canvas, ReflectGlassBackdrop backdrop,
                                        float left, float right, boolean capture) {
        if (pageContent == null && departingPage == null && navigation(screen)) return;
        if (capture) backdrop.draw(canvas, left, 765, right, 824, 29.5f, 10, navigationSource);
        else backdrop.replay(canvas, left, 765, right, 824, 29.5f, 10);
    }

    private void drawMenu(Canvas c) {
        c.save();
        c.clipPath(glass);
        for (Shader fill : MENU_FILL) {
            paint.setShader(fill);
            c.drawPath(glass, paint);
        }
        paint.setShader(null);
        c.restore();
        outline(c, 175, 617, 377, 749, 26, MENU_EDGE);
        artwork.icon(c, "attach", 191, 631);
        artwork.icon(c, "mic_menu", 191, 675);
        artwork.icon(c, "headset", 191, 719);
        text(c, "Прикрепить файл", 219.5f, 646, 16, WHITE, artwork.medium, false);
        text(c, "Записать сейчас", 219, 690, 16, WHITE, artwork.medium, false);
        text(c, "Звонок", 219, 734, 16, WHITE, artwork.medium, false);
    }

    private void drawRecorder(Canvas c, Screen page) {
        if (callMode && page != Screen.REVIEW) {
            artwork.icon(c, "headset", 184.5f, 70);
            text(c, "Звонок", 196.5f, 123, 18, WHITE, artwork.medium, true);
        }
        if (orbEdgeShade == null) orbEdgeShade = orbEdgeShade();
        long now = SystemClock.uptimeMillis();
        c.save();
        c.clipRect(65, 251, 329, 529);
        float[] vertices = null;
        if (page == Screen.RECORDING || page == Screen.PAUSED) {
            c.rotate(recordingVisuals.angle(now), RecordingOrb.CENTER_X, RecordingOrb.CENTER_Y);
            float morph = recordingVisuals.morphAmount(now);
            if (morph > 0) {
                if (recordingOrb == null) recordingOrb = new RecordingOrb();
                vertices = recordingOrb.vertices(now, morph);
            }
        }
        artwork.recordingOrb(c, vertices);
        c.restore();
        c.drawBitmap(orbEdgeShade, 260, 217, null);
        switch (page) {
            case RECORDING -> {
                float reveal = recordingVisuals.reveal(now);
                int ink = Color.argb(Math.round(reveal * 255), 255, 255, 255);
                text(c, "Распознаю голос...", 196.5f, 412.3f + 5 * (1 - reveal), 18, ink, artwork.display, true);
                liveWaveform(c, now, reveal, ink);
                artworkButton(c, WHITE, "pause", 274.75f, 794.9f, 0);
            }
            case REVIEW -> {
                text(c, "Готово!", 196.5f, 412.3f, 18, WHITE, artwork.display, true);
                drawReview(c);
                artworkButton(c, 0xff0088ff, "send", 275.26f, 794.75f, 90);
            }
            default -> artworkButton(c, WHITE, "mic_start", 274.5f, 794.5f, 0);
        }
        if (page == Screen.RECORDING || page == Screen.PAUSED) {
            text(c, elapsed, 32, 803, 26, WHITE, artwork.display, false);
            pill(c, FINISH_LEFT, 765, FINISH_LEFT + 59, 824, PLUS_EDGE);
            rounded(c, FINISH_CENTER_X - 8, 786.5f, FINISH_CENTER_X + 8, 802.5f, 2.5f, WHITE);
        }
        closeButton(c, "plus_recorder", -45);
    }

    private void liveWaveform(Canvas c, long now, float reveal, int ink) {
        float progress = !preferences.motionEnabled() ? 1 : recordingVisuals.sampleProgress(now);
        for (int i = 0; i < RecordingVisuals.BARS; i++) {
            float x = 45 + 302 * i / (float) (RecordingVisuals.BARS - 1);
            float h = recordingVisuals.height(i, progress) * reveal;
            rounded(c, x, 627 - h / 2, x + 2, 627 + h / 2, 1, ink);
        }
    }

    private void drawReview(Canvas c) {
        rounded(c, 84, 75, 306, 123, 24, 0xff2c2c2a);
        text(c, "Расшифровать в фоне", 195, 104.8f, 16, WHITE, artwork.medium, true);
        text(c, "Расшифровка выполняется", 196.5f, 657.3f, 16, 0xff999999, artwork.medium, true);
        text(c, "на устройстве", 196.5f, 678.3f, 16, 0xff999999, artwork.medium, true);
    }

    private void closeButton(Canvas c, String asset, float angle) {
        pill(c, 318, 765, 377, 824, PLUS_EDGE);
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

    private void pill(Canvas c, float x, float y, float right, float bottom, Shader stroke) {
        pill(c, x, y, right, bottom, PILL_FILL, stroke);
    }

    private void pill(Canvas c, float x, float y, float right, float bottom, Shader fill, Shader stroke) {
        paint.setColor(WHITE);
        paint.setShader(fill);
        c.drawRoundRect(x, y, right, bottom, 29.5f, 29.5f, paint);
        paint.setShader(null);
        outline(c, x, y, right, bottom, 29.5f, stroke);
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

    private static Shader edge(float x, float y, float right, float bottom, int top, int end) {
        return new LinearGradient(x, y, right, bottom, top, end, Shader.TileMode.CLAMP);
    }

    private void outline(Canvas c, float x, float y, float right, float bottom, float radius, Shader stroke) {
        paint.setColor(WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.7f);
        paint.setShader(stroke);
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
