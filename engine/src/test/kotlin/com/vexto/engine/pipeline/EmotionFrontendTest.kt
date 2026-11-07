package com.vexto.engine.pipeline

import com.vexto.engine.emotion.GigaAmMel
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.sin

class EmotionFrontendTest {
    @Test fun fftMatchesTheReferenceFrontendWithinFloatTolerance() {
        val reference = GigaAmMel()
        val fixtures = listOf(FloatArray(201), FloatArray(3200) { i ->
            (sin(i * .17) * .125 + ((i * 7919 % 127) - 63) / 1024.0).toFloat()
        }, FloatArray(6559) { i -> if (i % 397 == 0) .9f else -.007f })
        for (samples in fixtures) {
            val original = samples.copyOf()
            val expected = reference.logMel(samples)
            val actual = EmotionFrontend.logMel(samples)
            assertArrayEquals(expected, actual, 0.0002f)
            assertArrayEquals(original, samples, 0f)
        }
        // Intermediate calls cannot modify the cached tables or retain per-call work buffers.
        assertArrayEquals(reference.logMel(fixtures[1]), EmotionFrontend.logMel(fixtures[1]), 0.0002f)
    }
}
