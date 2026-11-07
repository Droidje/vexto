package com.vexto.engine.diarization

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.diarization.DiarizationTimeline.Range
import com.vexto.engine.diarization.DiarizationTimeline.SpeakerInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiarizationTimelineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun recordingsUpToFiveMinutesStayWhole() {
        val wav = temp.newFile("five.wav")
        PcmWave.Writer(wav).use { it.padSilence(300L * 16_000) }
        val plan = DiarizationTimeline.plan(wav)
        assertFalse(plan.compacted)
        assertEquals(listOf(Range(0, 300L * 16_000)), plan.kept)
    }

    @Test fun aLongPauseIsRemovedAndAShortPauseStays() {
        val speech = BooleanArray(220) { it < 50 || it in 180..199 }
        val kept = DiarizationTimeline.keep(220L * DiarizationTimeline.FRAME_SAMPLES, speech)
        assertEquals(listOf(
            Range(0, 50L * DiarizationTimeline.FRAME_SAMPLES + DiarizationTimeline.COLLAR_SAMPLES),
            Range(180L * DiarizationTimeline.FRAME_SAMPLES - DiarizationTimeline.COLLAR_SAMPLES,
                200L * DiarizationTimeline.FRAME_SAMPLES + DiarizationTimeline.COLLAR_SAMPLES),
        ), kept)
        val close = BooleanArray(200) { it < 50 || it in 55..70 }
        val merged = DiarizationTimeline.keep(200L * DiarizationTimeline.FRAME_SAMPLES, close)
        assertEquals(1, merged.size)
        assertTrue(merged.single().fromSample == 0L)
        assertTrue(merged.single().toSample > 70L * DiarizationTimeline.FRAME_SAMPLES)
    }

    @Test fun mappedTurnsKeepTheOriginalClockAndSplitAcrossARemovedPause() {
        val plan = DiarizationTimeline.Plan(1_000_000, listOf(Range(0, 36_800), Range(475_200, 516_800)), compacted = true)
        val second = DiarizationTimeline.remap(plan, listOf(SpeakerInterval("speaker_1", 2_300, 4_900)))
        assertEquals(listOf(SpeakerInterval("speaker_1", 29_700, 32_300)), second)
        val bridged = DiarizationTimeline.remap(plan, listOf(SpeakerInterval("speaker_2", 2_000, 3_000)))
        assertEquals(listOf(
            SpeakerInterval("speaker_2", 2_000, 2_300),
            SpeakerInterval("speaker_2", 29_700, 30_400),
        ), bridged)
        assertTrue(bridged.none { it.startMs in 2_301..29_699 })
    }

    @Test fun energyScanCompactsALongSilentRecordingAndCopiesOnlySpeech() {
        val wav = temp.newFile("long.wav")
        PcmWave.Writer(wav).use { writer ->
            writer.write(FloatArray(2 * 16_000) { 0.2f })
            writer.padSilence(28L * 16_000)
            writer.write(FloatArray(2 * 16_000) { 0.2f })
            writer.padSilence(328L * 16_000)
        }
        val plan = DiarizationTimeline.plan(wav)
        assertTrue(plan.compacted)
        assertEquals(2, plan.kept.size)
        assertEquals(0L, plan.kept[0].fromSample)
        assertTrue(plan.omittedSamples > DiarizationTimeline.MIN_OMITTED_SAMPLES)
        val compact = temp.newFile("compact.wav")
        DiarizationTimeline.writeCompact(wav, compact, plan)
        val info = PcmWave.inspect(compact)
        assertEquals(plan.kept.sumOf { it.toSample - it.fromSample }, info.sampleCount)
        val head = PcmWave.readFloat(compact, 0, 320)
        assertTrue(head.all { it > 0.1f })
    }
}
