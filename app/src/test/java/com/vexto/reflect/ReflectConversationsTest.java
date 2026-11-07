package com.vexto.reflect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReflectConversationsTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("conversations.db");
    }

    @After public void cleanup() {
        context.deleteDatabase("conversations.db");
    }

    @Test public void listsShowTheDateOnlyOutsideTheCurrentDay() {
        var recorded = ZonedDateTime.parse("2026-11-07T08:12:00+03:00");
        var today = recorded.toLocalDate();

        assertEquals("08:12", ReflectCatalog.timeLabel(recorded, today));
        assertEquals("06.11 · 08:12", ReflectCatalog.timeLabel(recorded.minusDays(1), today));
        assertEquals("07.11.25 · 08:12", ReflectCatalog.timeLabel(recorded.minusYears(1), today));
    }

    @Test public void numbersFollowRecordedFilesAcrossRenameReorderAndReopen() {
        var oldest = recording(new File("oldest.wav"), "Запись №2");
        var newest = recording(new File("newest.wav"), "Запись №4");
        try (var store = new ReflectConversationStore(context)) {
            var initial = ReflectCatalog.localConversations(List.of(newest, oldest), store);
            assertEquals("Разговор №2", initial.get(0).title);
            assertEquals("Разговор №1", initial.get(1).title);
        }

        try (var store = new ReflectConversationStore(context)) {
            var renamed = recording(newest.file, "Новая встреча");
            var added = recording(new File("added.wav"), "Запись №1");
            var after = ReflectCatalog.localConversations(List.of(renamed, added), store);
            assertEquals("Разговор №2", after.get(0).title);
            assertEquals(newest.file, after.get(0).recording.file);
            assertEquals("Разговор №3", after.get(1).title);
            assertEquals("Разговор №1", store.title(oldest.file));
        }
    }

    @Test public void searchFindsWordsBeyondThePreviewAndCombinesThemWithMetadata() throws Exception {
        File directory = new File(context.getFilesDir(), "recordings");
        Files.createDirectories(directory.toPath());
        File first = Files.write(new File(directory, "first.wav").toPath(), new byte[]{1}).toFile();
        var transcripts = new ReflectTranscriptStore(context.getFilesDir());
        transcripts.save(first, new ReflectTranscript(List.of(
                new ReflectTranscript.Line("speaker", 0, 500, "Предпросмотр"),
                new ReflectTranscript.Line("speaker", 500, 900, "Подтверждённый итог"))));
        try (var store = new ReflectConversationStore(context)) {
            var rows = ReflectCatalog.localConversations(List.of(recording(first, "Совещание")), store);
            assertEquals(rows, ReflectCatalog.searchConversations(rows, "АННА подтвержденный №1", transcripts));
            assertTrue(ReflectCatalog.searchConversations(rows, "Анна неизвестное", transcripts).isEmpty());
        }
    }

    @Test public void playbackFindsActiveLinesAndRespectsSilenceAndOverlaps() {
        var timeline = new ReflectPlaybackTimeline(List.of(
                new ReflectTranscript.Line("first", 100, 400, "один"),
                new ReflectTranscript.Line("second", 200, 300, "два"),
                new ReflectTranscript.Line("third", 600, 900, "три")));
        assertEquals(-1, timeline.at(99));
        assertEquals(0, timeline.at(100));
        assertEquals(1, timeline.at(200));
        assertEquals(0, timeline.at(300));
        assertEquals(-1, timeline.at(400));
        assertEquals(2, timeline.at(600));
        assertEquals(-1, timeline.at(900));
    }

    @Test public void seekingInSilenceFollowsTheUpcomingLineOrLastAtTheEnd() {
        var timeline = new ReflectPlaybackTimeline(List.of(
                new ReflectTranscript.Line("first", 100, 400, "один"),
                new ReflectTranscript.Line("second", 200, 300, "два"),
                new ReflectTranscript.Line("third", 600, 900, "три")));
        assertEquals(-1, new ReflectPlaybackTimeline(List.of()).atSeek(0));
        assertEquals(0, timeline.atSeek(0));
        assertEquals(1, timeline.atSeek(200));
        assertEquals(0, timeline.atSeek(301));
        assertEquals(2, timeline.atSeek(400));
        assertEquals(2, timeline.atSeek(900));
    }

    @Test public void waveformAccessibilityReportsRangeAndSeeksWithoutTogglingPlayback() {
        var target = new AtomicReference<Float>();
        var player = new ReflectConversationPlayer(context, new ReflectArtwork(context.getAssets()),
                new ReflectPreferences(context), "0:30", "разговор", () -> {
                    throw new AssertionError("Seeking must preserve playback state");
                }, target::set);
        player.update(false, 6_000, 30_000);
        var waveform = player.getChildAt(1);
        var info = waveform.createAccessibilityNodeInfo();
        assertEquals("android.widget.SeekBar", info.getClassName());
        assertEquals(30f, info.getRangeInfo().getMax(), 0f);
        assertEquals(6f, info.getRangeInfo().getCurrent(), 0f);

        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 12f);
        assertTrue(waveform.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
        assertEquals(.4f, target.get(), .0001f);
        assertTrue(waveform.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
        assertEquals(1f / 30f, target.get(), .0001f);
    }

    private static ReflectCatalog.Recording recording(File file, String title) {
        return new ReflectCatalog.Recording(file, title, "Сегодня", "12:00", "0:01", "Предпросмотр",
                List.of("Анна"));
    }
}
