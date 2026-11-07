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

    private val passthrough = inRate == outRate
    private val up: Int
    private val down: Int
    private val phases: Int
    private val half: Int
    private val table: FloatArray

    private var buffer: FloatArray
    private var bufferStart = 0L
    private var bufferSize = 0
    private var produced = 0L
    private val out = FloatArray(1024)
    private var useNative = false

    init {
        require(inRate > 0 && outRate > 0 && zeroCrossings > 0)
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        phases = min(up, MaxPhases)
        val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.95
        half = if (passthrough) 0 else ceil(zeroCrossings / cutoff).toInt()
        table = FloatArray(phases * 2 * half) { index ->
            val p = index / (2 * half)
            val i = index % (2 * half)
            val frac = p.toDouble() / phases
            val d = frac + (half - 1 - i)
            (cutoff * sinc(cutoff * d) * blackman(d / (half + 1))).toFloat()
        }
        buffer = if (passthrough) FloatArray(0) else FloatArray(InputBlock + 2 * half)
        useNative = !passthrough && NativeResampler.available
        // Тишина перед началом записи: первым выходам не нужна проверка границ
        bufferStart = -(half - 1).coerceAtLeast(0).toLong()
        bufferSize = (half - 1).coerceAtLeast(0)
    }

    /** Добавляет [count] отсчётов. [sink] должен прочитать буфер до возврата из вызова. */
    fun process(input: FloatArray, count: Int, sink: (FloatArray, Int) -> Unit) {
        require(count in 0..input.size)
        if (passthrough) {
            if (count > 0) sink(input, count)
            return
        }
        var offset = 0
        while (offset < count) {
            val n = min(InputBlock, count - offset)
            append(input, offset, n)
            drain(sink)
            offset += n
        }
    }

    /** Дополняет вход тишиной и отдаёт хвост. */
    fun flush(sink: (FloatArray, Int) -> Unit) {
        if (passthrough) return
        val total = bufferStart + bufferSize
        append(FloatArray(half + 1), 0, half + 1)
        drain(sink, inputEnd = total)
    }

    private fun drain(sink: (FloatArray, Int) -> Unit, inputEnd: Long = Long.MAX_VALUE) {
        val available = bufferStart + bufferSize
        val limit = minOf(available - half, inputEnd)
        val end = if (limit <= 0) 0 else (limit * up + down - 1) / down
        while (produced < end) {
            val n = minOf(out.size.toLong(), end - produced).toInt()
            var rendered = false
            if (useNative && n >= 32) {
                try {
                    rendered = NativeResampler.render(buffer, table, out, bufferStart, produced, up, down, phases, half, n)
                    if (!rendered) useNative = false
                } catch (_: LinkageError) {
                    // JVM tests, absent ABI or an older installed library retain the Kotlin path.
                    useNative = false
                }
            }
            if (!rendered) {
                for (j in 0 until n) {
                    val pos = (produced + j) * down
                    val base = pos / up
                    val phase = ((pos % up) * phases / up).toInt()
                    val tapStart = phase * 2 * half
                    val k = (base - half + 1 - bufferStart).toInt()
                    var acc = 0f
                    for (i in 0 until 2 * half) acc += table[tapStart + i] * buffer[k + i]
                    out[j] = acc
                }
            }
            produced += n
            sink(out, n)
        }
        compact()
    }

    private fun append(input: FloatArray, offset: Int, count: Int) {
        if (bufferSize + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, bufferSize + count))
        System.arraycopy(input, offset, buffer, bufferSize, count)
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
        const val InputBlock = 8192

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        fun blackman(x: Double): Double {
            if (x <= -1.0 || x >= 1.0) return 0.0
            val t = (x + 1) / 2
            return 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        }
    }
}

/** One call filters a full output block; the optional library owns no persistent memory. */
private object NativeResampler {
    val available = try {
        System.loadLibrary("vexto_resampler")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    external fun render(
        input: FloatArray, taps: FloatArray, output: FloatArray,
        bufferStart: Long, produced: Long, up: Int, down: Int,
        phases: Int, half: Int, count: Int,
    ): Boolean
}
