package com.vexto.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    private fun resample(input: FloatArray, from: Int, to: Int, chunk: Int = 1000): FloatArray {
        val out = ArrayList<Float>()
        val sink = { buf: FloatArray, n: Int -> for (i in 0 until n) out += buf[i] }
        val r = Resampler(from, to)
        var pos = 0
        while (pos < input.size) {
            val n = minOf(chunk, input.size - pos)
            r.process(input.copyOfRange(pos, pos + n), n, sink)
            pos += n
        }
        r.flush(sink)
        return out.toFloatArray()
    }

    private fun sine(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    @Test
    fun `length follows rate ratio`() {
        assertEquals(16_000, resample(FloatArray(44_100), 44_100, 16_000).size)
        assertEquals(16_000, resample(FloatArray(48_000), 48_000, 16_000).size)
        assertEquals(16_000, resample(FloatArray(8_000), 8_000, 16_000).size)
        assertEquals(364, resample(FloatArray(1001), 44_100, 16_000).size)
        assertEquals(0, resample(FloatArray(0), 48_000, 16_000).size)
    }

    @Test
    fun `equal rates pass samples through immediately without filtering or tail`() {
        val input = floatArrayOf(0.25f, -0.75f, 1f, 0f)
        val r = Resampler(16_000, 16_000)
        var emitted = 0
        r.process(input, 3) { buffer, count ->
            assertTrue(buffer === input)
            assertEquals(3, count)
            for (i in 0 until count) assertEquals(input[i], buffer[i], 0f)
            emitted += count
        }
        r.process(input, 0) { _, _ -> throw AssertionError("Empty input must not emit") }
        r.flush { _, _ -> throw AssertionError("Passthrough must not append a tail") }
        assertEquals(3, emitted)
    }

    @Test
    fun `passband tone keeps amplitude and phase`() {
        val out = resample(sine(1000.0, 44_100, 1.0), 44_100, 16_000)
        val expected = sine(1000.0, 16_000, 1.0)
        assertEquals(rms(expected, 1000, 15_000), rms(out, 1000, 15_000), 0.01)
        for (i in 1000 until 15_000) assertTrue(abs(out[i] - expected[i]) < 0.01f)
    }

    @Test
    fun `tone above new nyquist is suppressed`() {
        val out = resample(sine(11_000.0, 48_000, 1.0), 48_000, 16_000)
        assertTrue(rms(out, 1000, 15_000) < 0.005)
    }

    @Test
    fun `chunking does not change output`() {
        val input = sine(440.0, 22_050, 0.5)
        val a = resample(input, 22_050, 16_000, chunk = 37)
        val b = resample(input, 22_050, 16_000, chunk = input.size)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-6f)
    }
}
