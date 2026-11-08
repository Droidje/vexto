package com.vexto.engine.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Bounded PCM16 WAV I/O. All model views use the same integer sample clock. */
object PcmWave {
    data class Info(val sampleRate: Int, val sampleCount: Long, val dataOffset: Long)

    fun inspect(file: File): Info = RandomAccessFile(file, "r").use { input ->
        fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun uint(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        require(tag() == "RIFF") { "Expected RIFF WAV" }
        uint()
        require(tag() == "WAVE")
        var sampleRate = 0
        var validFormat = false
        while (input.filePointer + 8 <= input.length()) {
            val id = tag()
            val length = uint()
            val start = input.filePointer
            require(length <= input.length() - start) { "Truncated WAV chunk" }
            if (id == "fmt ") {
                require(length >= 16)
                val format = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                val channels = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                sampleRate = uint().toInt()
                uint()
                val alignment = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                val bits = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                require(format == 1 && channels == 1 && bits == 16 && alignment == 2 && sampleRate == 16000) {
                    "Expected mono PCM16/16000Hz"
                }
                validFormat = true
            } else if (id == "data") {
                require(validFormat && length % 2 == 0L)
                return@use Info(sampleRate, length / 2, start)
            }
            input.seek(start + length + length % 2)
        }
        error("WAV has no PCM data")
    }

    fun readFloat(file: File, startSample: Long, count: Int): FloatArray {
        val info = inspect(file)
        require(startSample >= 0 && count >= 0 && count <= 16000 * 60)
        require(startSample + count <= info.sampleCount)
        val bytes = ByteArray(count * 2)
        RandomAccessFile(file, "r").use { input ->
            input.seek(info.dataOffset + startSample * 2)
            input.readFully(bytes)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(count) { buffer.get(it) / 32768f }
    }

    /** Copy the exact PCM bytes: no quantization or gain changes between recognizers. */
    fun crop(input: File, output: File, from: Long, to: Long) {
        val info = inspect(input)
        require(from >= 0 && to in from..info.sampleCount)
        output.parentFile?.mkdirs()
        RandomAccessFile(output, "rw").use { target ->
            target.setLength(0)
            target.write(header(info.sampleRate, (to - from) * 2))
            RandomAccessFile(input, "r").use { source ->
                source.seek(info.dataOffset + from * 2)
                copy(source, target, (to - from) * 2)
            }
            target.fd.sync()
        }
    }

    /** Hash exactly the canonical WAV crop bytes without materializing another audio file. */
    fun cropSha256(input: File, from: Long, to: Long): String {
        val info = inspect(input)
        require(from >= 0 && to in from..info.sampleCount)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(header(info.sampleRate, (to - from) * 2))
        RandomAccessFile(input, "r").use { source ->
            source.seek(info.dataOffset + from * 2)
            val buffer = ByteArray(65536)
            var remaining = (to - from) * 2
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                source.readFully(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun fromPcm(input: File, output: File, samples: Long) {
        require(input.length() == samples * 2)
        output.parentFile?.mkdirs()
        RandomAccessFile(output, "rw").use { target ->
            target.setLength(0)
            target.write(header(16000, samples * 2))
            RandomAccessFile(input, "r").use { copy(it, target, samples * 2) }
            target.fd.sync()
        }
    }

    private fun copy(input: RandomAccessFile, output: RandomAccessFile, bytes: Long) {
        val buffer = ByteArray(65536)
        var left = bytes
        while (left > 0) {
            val n = minOf(buffer.size.toLong(), left).toInt()
            input.readFully(buffer, 0, n)
            output.write(buffer, 0, n)
            left -= n
        }
    }

    internal fun header(rate: Int, bytes: Long): ByteArray {
        require(rate == 16000 && bytes in 0..0xffffffffL - 36) { "Recording exceeds RIFF size limit" }
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((bytes + 36).toInt()); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes.toInt())
        }.array()
    }

    class Writer(file: File, private val sampleRate: Int = 16000) : Closeable {
        private val output = RandomAccessFile(file.apply { parentFile?.mkdirs() }, "rw")
        private var samples = 0L
        private var closed = false
        init { output.setLength(0); output.write(header(sampleRate, 0)) }
        fun write(values: FloatArray) {
            check(!closed)
            val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { value -> buffer.putShort((value.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767).toShort()) }
            output.write(buffer.array())
            samples += values.size
        }
        fun padSilence(count: Long) {
            require(count >= 0)
            var remaining = count * 2
            val zeros = ByteArray(65536)
            while (remaining > 0) {
                val n = minOf(remaining, zeros.size.toLong()).toInt()
                output.write(zeros, 0, n); remaining -= n
            }
            samples += count
        }
        override fun close() {
            if (closed) return
            closed = true
            try { output.seek(0); output.write(header(sampleRate, samples * 2)); output.fd.sync() }
            finally { output.close() }
        }
    }
}
