package com.vexto.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmentsTest {

    @Test
    fun `padding keeps segments disjoint and inside audio`() {
        val padded = SpeechSegments.pad(listOf(Segment(100, 1000), Segment(1100, 2000)), 200, 2100)
        assertEquals(listOf(Segment(0, 1200), Segment(1200, 2100)), padded)
    }

    @Test
    fun `long segment is cut at the quietest frame`() {
        val rate = 16_000
        val samples = FloatArray(30 * rate) { 0.3f }
        // Тишина на 15-й секунде: туда и должен прийтись разрез
        for (i in 15 * rate until 15 * rate + 1600) samples[i] = 0f
        val parts = SpeechSegments.splitLong(Segment(0, samples.size.toLong()), 20 * rate) { a, b ->
            samples.copyOfRange(a.toInt(), b.toInt())
        }
        assertEquals(2, parts.size)
        assertTrue(parts[0].end in (15L * rate)..(15L * rate + 1600))
        assertEquals(samples.size.toLong(), parts[1].end)
    }
}
