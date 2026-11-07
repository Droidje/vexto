package com.vexto.engine.audio

import com.vexto.engine.model.SampleRate
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Моно PCM16 16 кГц: собственный временный PCM или заимствованное аудио внутри WAV.
 * В памяти держится только
 * энергия по кадрам 10 мс, из неё строится медленная АРУ для тихих голосов.
 */
class PcmAudio private constructor(
    val file: File,
    val sampleCount: Long,
    private val frameEnergy: FloatArray,
    private val dataOffset: Long,
    private val deleteOnClose: Boolean,
) : Closeable {

    /** Existing decoder/writer ownership: raw PCM starts at zero and is deleted on close. */
    internal constructor(file: File, sampleCount: Long, frameEnergy: FloatArray) :
        this(file, sampleCount, frameEnergy, dataOffset = 0, deleteOnClose = true)

    val durationMs: Long get() = sampleCount * 1000 / SampleRate

    private var gains: FloatArray? = null

    /** Отсчёты [from, to) как float в диапазоне [-1, 1]; с [agc] тихие участки усилены. */
    fun read(from: Long, to: Long, agc: Boolean = false): FloatArray {
        val start = from.coerceIn(0, sampleCount)
        val end = to.coerceIn(start, sampleCount)
        val n = (end - start).toInt()
        val bytes = ByteArray(n * 2)
        RandomAccessFile(file, "r").use {
            it.seek(dataOffset + start * 2)
            it.readFully(bytes)
        }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(n) { shorts.get(it) / 32768f }
        if (agc) applyGain(out, start)
        return out
    }

    /**
     * Усиление по окну [AgcWindowFrames] кадров: target / rms, но не меньше 1 и не больше
     * [AgcMaxGain]. Громкие участки не трогаются, тихий собеседник подтягивается к громкому.
     */
    private fun gainCurve(): FloatArray = gains ?: run {
        val n = frameEnergy.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + frameEnergy[i]
        val halfWindow = AgcWindowFrames / 2
        FloatArray(n) { i ->
            val a = maxOf(0, i - halfWindow)
            val b = minOf(n, i + halfWindow + 1)
            val rms = sqrt((prefix[b] - prefix[a]) / (b - a) + 1e-10)
            (AgcTarget / rms).toFloat().coerceIn(1f, AgcMaxGain)
        }.also { gains = it }
    }

    private fun applyGain(samples: FloatArray, offset: Long) {
        val curve = gainCurve()
        if (curve.isEmpty()) return
        for (i in samples.indices) {
            // Линейная интерполяция между центрами кадров
            val pos = (offset + i - FrameSize / 2).toDouble() / FrameSize
            val k = pos.toInt().coerceIn(0, curve.size - 1)
            val next = minOf(k + 1, curve.size - 1)
            val t = (pos - k).toFloat().coerceIn(0f, 1f)
            val g = curve[k] + (curve[next] - curve[k]) * t
            samples[i] = (samples[i] * g).coerceIn(-1f, 1f)
        }
    }

    /** RMS envelope for the 32 playback bars; the quietest sections retain the dot baseline. */
    internal fun previewWaveform(): IntArray {
        if (frameEnergy.isEmpty()) return intArrayOf()
        val bins = FloatArray(32)
        frameEnergy.forEachIndexed { i, energy ->
            val bin = i * bins.size / frameEnergy.size
            bins[bin] = maxOf(bins[bin], energy)
        }
        val max = bins.max()
        return IntArray(bins.size) { i ->
            if (max > 0f) 2 + (20 * sqrt(bins[i] / max)).roundToInt() else 2
        }
    }

    override fun close() {
        if (deleteOnClose) file.delete()
    }

    companion object {
        /** Read-only view: callers retain ownership of the original WAV. */
        internal fun borrowedWav(file: File, sampleCount: Long, frameEnergy: FloatArray, dataOffset: Long): PcmAudio {
            require(dataOffset >= 0 && sampleCount >= 0)
            return PcmAudio(file, sampleCount, frameEnergy, dataOffset, deleteOnClose = false)
        }

        const val FrameSize = SampleRate / 100
        private const val AgcWindowFrames = 50
        private const val AgcTarget = 0.1
        private const val AgcMaxGain = 10f
    }
}

/** Пишет float-отсчёты 16 кГц в PCM16 и попутно считает энергию кадров. */
internal class PcmWriter(private val file: File) : DecodedSampleSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val bytes = ByteBuffer.allocate(2 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private var energy = FloatArray(1024)
    private var frames = 0
    private var frameSum = 0.0
    private var frameFill = 0
    private var closed = false
    override var sampleCount = 0L
        private set

    override fun write(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            val s = samples[i].coerceIn(-1f, 1f)
            bytes.putShort((s * 32767f).toInt().toShort())
            if (!bytes.hasRemaining()) flushBytes()
            frameSum += s * s
            if (++frameFill == PcmAudio.FrameSize) pushFrame()
        }
        sampleCount += count
    }

    fun finish(): PcmAudio {
        if (frameFill > 0) pushFrame()
        close()
        return PcmAudio(file, sampleCount, energy.copyOf(frames))
    }

    private fun pushFrame() {
        if (frames == energy.size) energy = energy.copyOf(frames * 2)
        energy[frames++] = (frameSum / frameFill).toFloat()
        frameSum = 0.0
        frameFill = 0
    }

    private fun flushBytes() {
        out.write(bytes.array(), 0, bytes.position())
        bytes.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        flushBytes()
        out.close()
    }
}
