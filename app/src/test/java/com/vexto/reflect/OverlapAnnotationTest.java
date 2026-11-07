package com.vexto.reflect;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import com.vexto.engine.model.EmotionProbabilities;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OverlapAnnotationTest {
    private static ReflectTranscript.Line line(Boolean detected) {
        return new ReflectTranscript.Line("speaker_1", 0, 4000, "Полная реплика", null, "Анна", detected);
    }

    @Test public void preservesTrueFalseAndUnknownWithoutChangingSpeakerRevisionOrSubmission() throws Exception {
        var unknown = new ReflectTranscript(List.of(line(null)));
        for (Boolean detected : new Boolean[]{null, false, true}) {
            var source = new ReflectTranscript(List.of(line(detected)));
            var parsed = ReflectTranscript.parse(new ByteArrayInputStream(source.encode()));
            assertEquals(detected, parsed.lines.get(0).overlapDetected);
            assertTrue(source.sameLines(parsed));
            assertArrayEquals(unknown.encodeSpeakerRevision(), source.encodeSpeakerRevision());
            var submission = new ByteArrayOutputStream();
            source.writeSubmission(submission);
            String json = submission.toString(StandardCharsets.UTF_8);
            assertFalse(json.contains("overlap_detected"));
            assertEquals("Полная реплика", ReflectTranscript.parse(
                    new ByteArrayInputStream(submission.toByteArray())).lines.get(0).text);
        }
        assertFalse(unknown.sameLines(new ReflectTranscript(List.of(line(false)))));
        assertFalse(new ReflectTranscript(List.of(line(false))).sameLines(new ReflectTranscript(List.of(line(true)))));
    }

    @Test public void rejectsNonBooleanAndDuplicateAnnotations() throws Exception {
        String source = new String(new ReflectTranscript(List.of(line(true))).encode(), StandardCharsets.UTF_8);
        for (String invalid : List.of("null", "1", "\"true\"", "truE", "falsee", "false,\"overlap_detected\":true")) {
            String json = source.replace("\"overlap_detected\":true", "\"overlap_detected\":" + invalid);
            assertThrows(IOException.class, () -> ReflectTranscript.parse(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test public void backgroundMetadataSurvivesResumeAndNegativeCanClearPositive() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        File directory = new File(context.getFilesDir(), "recordings");
        Files.createDirectories(directory.toPath());
        File recording = Files.write(new File(directory, "overlap.wav").toPath(), new byte[]{1}).toFile();
        String job = PendingTranscription.create(context, recording).jobId();
        var store = new ReflectTranscriptStore(context.getFilesDir());
        var base = new ReflectTranscript(List.of(line(null)));
        assertTrue(store.saveEngineFinal(recording, job, base).owned());
        assertTrue(store.saveEngineEmotions(recording, job, new ReflectTranscript(List.of(line(true)))).owned());
        var emotionOnly = new ReflectTranscript(List.of(new ReflectTranscript.Line(
                "speaker_1", 0, 4000, "Полная реплика", new EmotionProbabilities(1, 0, 0, 0), "Другое имя")));
        assertTrue(store.saveEngineEmotions(recording, job, emotionOnly).owned());
        var reopened = new ReflectTranscriptStore(context.getFilesDir());
        assertEquals(Boolean.TRUE, reopened.load(recording).lines.get(0).overlapDetected);
        assertEquals("Анна", reopened.load(recording).lines.get(0).speakerName);
        assertEquals(Boolean.TRUE, reopened.saveEngineFinal(recording, job, base).transcript().lines.get(0).overlapDetected);
        assertTrue(reopened.saveEngineEmotions(recording, job, new ReflectTranscript(List.of(line(false)))).owned());
        assertEquals(Boolean.FALSE, reopened.load(recording).lines.get(0).overlapDetected);
    }

    @Test public void recheckPreservesAudioEvidenceOnlyForTheExactSameInterval() {
        var selected = line(true);
        var original = new ReflectTranscript(List.of(selected));
        var sameInterval = UtteranceRecheck.apply(original, selected, 0, 5000,
                List.of(new UtteranceRecheck.Piece(0, 4000, "Исправленные слова", null)));
        assertEquals(Boolean.TRUE, sameInterval.lines.get(0).overlapDetected);
        var changedInterval = UtteranceRecheck.apply(original, selected, 0, 5000,
                List.of(new UtteranceRecheck.Piece(100, 4000, "Другой интервал", null)));
        assertNull(changedInterval.lines.get(0).overlapDetected);
        var rescored = UtteranceRecheck.apply(original, selected, 0, 5000,
                List.of(new UtteranceRecheck.Piece(100, 4000, "Перепроверено моделью", null, false)));
        assertEquals(Boolean.FALSE, rescored.lines.get(0).overlapDetected);
        var clipped = UtteranceRecheck.apply(original, selected, 0, 4500,
                List.of(new UtteranceRecheck.Piece(100, 5000, "Обрезанный интервал", null, true)));
        assertNull(clipped.lines.get(0).overlapDetected);
    }

    @Test public void engineConversionPreservesTheModelResult() {
        var result = new com.vexto.engine.model.Transcript(List.of(
                new com.vexto.engine.model.Utterance("speaker_1", 0, 4000, "Реплика", List.of(), null, true)), List.of());
        assertEquals(Boolean.TRUE, ReflectTranscriptionService.convert(result).lines.get(0).overlapDetected);
    }

    @Test public void recycledAvatarShowsOnlyConfirmedAlertsAndUsesTheCurrentLine() {
        var context = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        var clicked = new AtomicReference<ReflectTranscript.Line>();
        var actions = (ReflectTranscriptView.Actions) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ReflectTranscriptView.Actions.class}, (proxy, method, args) -> {
                    if (method.getName().equals("openOverlapInfo")) clicked.set((ReflectTranscript.Line) args[0]);
                    return null;
                });
        var confirmed = line(true);
        var negative = new ReflectTranscript.Line("speaker_2", 4000, 8000, "Короткий ответ", null, null, false);
        var unknown = new ReflectTranscript.Line("speaker_3", 8000, 9000, "Не проверено");
        var page = new ReflectTranscriptView(context, new ReflectArtwork(context.getAssets()), new ReflectPreferences(context), actions);
        page.content("Диалог", "0:09", 9000, new ReflectTranscript(List.of(confirmed, negative, unknown)),
                false, false, null, false, null, null);
        layout(page, 852);
        ListView list = findList(page);
        assertNotNull(list);
        ListAdapter adapter = list.getAdapter();
        if (adapter instanceof HeaderViewListAdapter headers) adapter = headers.getWrappedAdapter();
        ViewGroup row = (ViewGroup) adapter.getView(0, null, list);
        // Android posts a tap's performClick callback through its attached window.
        context.setContentView(row);
        layout(row, 80);
        ViewGroup avatar = (ViewGroup) row.getChildAt(0);
        assertEquals(View.VISIBLE, avatar.getChildAt(1).getVisibility());
        assertEquals("android.widget.Button", avatar.createAccessibilityNodeInfo().getClassName());
        assertTrue(avatar.getContentDescription().toString().contains("Обнаружено перебивание"));
        assertNotNull(row.getTouchDelegate());
        assertTrue(avatar.performClick());
        assertSame(confirmed, clicked.get());
        clicked.set(null);
        // The badge extends beyond the visual avatar; its outer edge still opens the panel.
        float x = avatar.getRight() + 2, y = avatar.getTop() + 12;
        MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(100, 120, MotionEvent.ACTION_UP, x, y, 0);
        try {
            assertTrue(row.dispatchTouchEvent(down));
            assertTrue(row.dispatchTouchEvent(up));
        } finally {
            down.recycle();
            up.recycle();
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertSame(confirmed, clicked.get());

        for (int index : new int[]{1, 2}) {
            clicked.set(null);
            assertSame(row, adapter.getView(index, row, list));
            assertEquals(View.GONE, avatar.getChildAt(1).getVisibility());
            assertFalse(avatar.isClickable());
            assertNull(avatar.getContentDescription());
            assertNull(row.getTouchDelegate());
            avatar.performClick();
            assertNull(clicked.get());
        }
        adapter.getView(0, row, list);
        avatar.performClick();
        assertSame(confirmed, clicked.get());
    }

    private static void layout(View view, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 393, height);
    }

    private static ListView findList(View view) {
        if (view instanceof ListView list) return list;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            ListView found = findList(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }
}
