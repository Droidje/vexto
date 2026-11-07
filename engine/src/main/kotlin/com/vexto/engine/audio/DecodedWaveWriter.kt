package com.vexto.engine.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface DecodedSampleSink : Closeable {
    val sampleCount: Long
    fun write(samples: FloatArray, count: Int)
    /** Return false without consuming input when this sink needs floating-point samples. */
    fun writePcm16(bytes: ByteBuffer, count: Int): Boolean = false
}

/** One bounded buffer, one final WAV, no unused waveform/AGC calculation in the inference path. */
internal class DecodedWaveWriter(file: File) : DecodedSampleSink {
    private val output = RandomAccessFile(file.apply { parentFile?.mkdirs() }, "rw")
    private val header = PcmWave.header(16000, 0)
    private val bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false
    override var sampleCount = 0L
        private set

    init { output.setLength(0); output.write(header) }

    override fun write(samples: FloatArray, count: Int) {
        check(!closed)
        require(count in 0..samples.size)
        for (i in 0 until count) {
            // Same conversion as the original PcmWriter for resampled/float data.
            val sample = samples[i].coerceIn(-1f, 1f)
            bytes.putShort((sample * 32767f).toInt().toShort())
            if (!bytes.hasRemaining()) flush()
        }
        sampleCount += count
    }

    override fun writePcm16(bytes: ByteBuffer, count: Int): Boolean {
        check(!closed)
        require(count >= 0 && count.toLong() * 2 <= bytes.remaining())
        var left = count * 2
        while (left > 0) {
            val length = minOf(left, this.bytes.remaining())
            val at = this.bytes.position()
            bytes.get(this.bytes.array(), at, length)
            this.bytes.position(at + length)
            left -= length
            if (!this.bytes.hasRemaining()) flush()
        }
        sampleCount += count
        return true
    }

    fun finish(): PcmWave.Info {
        check(!closed)
        flush()
        output.seek(0)
        output.write(PcmWave.header(16000, sampleCount * 2))
        output.fd.sync()
        close()
        return PcmWave.Info(16000, sampleCount, header.size.toLong())
    }

    private fun flush() {
        if (bytes.position() > 0) output.write(bytes.array(), 0, bytes.position())
        bytes.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        output.close()
    }
}


/** Canonical WAV input needs only a validated, cancellable PCM copy into the job-owned file. */
internal object CanonicalWaveDecoder {
    suspend fun copyIfCompatible(source: File, output: File, maxSamples: Long,
                                 progress: (Float) -> Unit): PcmWave.Info? {
        require(source.canonicalFile != output.canonicalFile)
        val known = runCatching { PcmWave.inspect(source) }.getOrNull() ?: return null
        if (known.sampleCount > maxSamples) throw AudioInputTooLongException()
        DecodedWaveWriter(output).use { writer ->
            RandomAccessFile(source, "r").use { input ->
                input.seek(known.dataOffset)
                val bytes = ByteArray(64 * 1024)
                var remaining = known.sampleCount * 2
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(remaining, bytes.size.toLong()).toInt()
                    input.readFully(bytes, 0, count)
                    writer.writePcm16(ByteBuffer.wrap(bytes, 0, count), count / 2)
                    remaining -= count
                    progress(1f - remaining.toFloat() / (known.sampleCount * 2).coerceAtLeast(1))
                }
            }
            return writer.finish()
        }
    }
}
