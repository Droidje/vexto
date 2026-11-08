package com.vexto.engine.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecodedWaveWriterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun directWavMatchesLegacyFloatConversionWithoutIntermediatePcm() {
        val samples = FloatArray(65_539) { ((it % 1001) - 500) / 500f }
        val legacy = temp.newFile("legacy.pcm")
        PcmWriter(legacy).apply { write(samples, samples.size) }.finish()
        val wave = temp.newFile("direct.wav")
        val writer = DecodedWaveWriter(wave)
        writer.write(samples, 1)
        writer.write(samples.copyOfRange(1, samples.size), samples.size - 1)
        val info = writer.finish()
        assertEquals(samples.size.toLong(), info.sampleCount)
        assertEquals(44L + 2L * samples.size, wave.length())
        assertArrayEquals(legacy.readBytes(), wave.readBytes().copyOfRange(44, wave.length().toInt()))
        assertEquals(info, PcmWave.inspect(wave))
    }

    @Test fun canonicalCopyPreservesPcmBitsAndRejectsUnsupportedOrOversizedInputBeforeWriting() = runBlocking {
        val source = temp.newFile("original.wav")
        val bytes = ByteBuffer.allocate(2 * 40_003).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(40_003) { putShort((it - 32_768).toShort()) }
        }.array()
        source.writeBytes(PcmWave.header(16000, bytes.size.toLong()) + bytes)
        val output = File(temp.root, "copied.wav")
        assertEquals(40_003L, CanonicalWaveDecoder.copyIfCompatible(source, output, 50_000) {}!!.sampleCount)
        assertArrayEquals(source.readBytes(), output.readBytes())
        val untouched = File(temp.root, "unsupported.wav")
        val unsupported = temp.newFile("48k.wav")
        unsupported.writeBytes(source.readBytes().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48_000).putInt(28, 96_000)
        })
        assertNull(CanonicalWaveDecoder.copyIfCompatible(unsupported, untouched, 50_000) {})
        assertFalse(untouched.exists())
        assertTrue(runCatching { CanonicalWaveDecoder.copyIfCompatible(source, untouched, 2) {} }.exceptionOrNull() is AudioInputTooLongException)
        assertFalse(untouched.exists())
        assertArrayEquals(PcmWave.header(16000, bytes.size.toLong()) + bytes, source.readBytes())
    }
}
