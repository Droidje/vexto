package com.vexto.engine.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Потоковый полифазный ресемплер с оконным sinc-фильтром (окно Блэкмана).
 *
 * Выход n соответствует моменту t = n * inRate / outRate во входных отсчётах. При понижении
 * частоты срез фильтра опускается ниже новой частоты Найквиста, поэтому наложения спектра нет.
 */
class Resampler(inRate: Int, outRate: Int, zeroCrossings: Int = 16) {

    private val up: Int
    private val down: Int
    private val phases: Int
    private val half: Int
    private val table: Array<FloatArray>

    private var buffer = FloatArray(4096)
    private var bufferStart = 0L
    private var bufferSize = 0
    private var produced = 0L

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        phases = min(up, MaxPhases)
        val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.95
        half = ceil(zeroCrossings / cutoff).toInt()
        table = Array(phases) { p ->
            val frac = p.toDouble() / phases
            FloatArray(2 * half) { i ->
                val d = frac + (half - 1 - i)
                (cutoff * sinc(cutoff * d) * blackman(d / (half + 1))).toFloat()
            }
        }
    }

    /** Добавляет [count] входных отсчётов и отдаёт готовые выходные в [sink]. */
    fun process(input: FloatArray, count: Int, sink: (FloatArray, Int) -> Unit) {
        append(input, count)
        drain(sink, flushing = false)
    }

    /** Дополняет вход тишиной и отдаёт хвост. */
    fun flush(sink: (FloatArray, Int) -> Unit) {
        val total = bufferStart + bufferSize
        append(FloatArray(half + 1), half + 1)
        drain(sink, flushing = true, inputEnd = total)
    }

    private fun drain(sink: (FloatArray, Int) -> Unit, flushing: Boolean, inputEnd: Long = Long.MAX_VALUE) {
        val out = FloatArray(1024)
        var n = 0
        val available = bufferStart + bufferSize
        while (true) {
            val pos = produced * down
            val base = pos / up
            if (base + half >= available) break
            if (flushing && base >= inputEnd) break
            val phase = ((pos % up) * phases / up).toInt()
            val taps = table[phase]
            var acc = 0f
            var k = (base - half + 1 - bufferStart).toInt()
            for (i in taps.indices) {
                if (k >= 0) acc += taps[i] * buffer[k]
                k++
            }
            out[n++] = acc
            produced++
            if (n == out.size) {
                sink(out, n)
                n = 0
            }
        }
        if (n > 0) sink(out, n)
        compact()
    }

    private fun append(input: FloatArray, count: Int) {
        if (bufferSize + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, bufferSize + count))
        System.arraycopy(input, 0, buffer, bufferSize, count)
        bufferSize += count
    }

    /** Отбрасывает отсчёты, которые больше не попадут ни в одно окно фильтра. */
    private fun compact() {
        val needed = (produced * down) / up - half + 1
        val drop = (needed - bufferStart).coerceIn(0, bufferSize.toLong()).toInt()
        if (drop == 0) return
        System.arraycopy(buffer, drop, buffer, 0, bufferSize - drop)
        bufferSize -= drop
        bufferStart += drop
    }

    private companion object {
        const val MaxPhases = 1024

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        fun blackman(x: Double): Double {
            if (x <= -1.0 || x >= 1.0) return 0.0
            val t = (x + 1) / 2
            return 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        }
    }
}
