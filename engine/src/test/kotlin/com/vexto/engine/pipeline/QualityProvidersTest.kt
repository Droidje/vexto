package com.vexto.engine.pipeline

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.SpeakerTurn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.math.ln

class QualityProvidersTest {
    @Test fun `native tokens keep punctuation case whitespace and relative offsets`() {
        val text = "Привет, мир!"
        val (tokens,words) = GigaTokenAlignment.align(text,arrayOf(" Привет",","," мир","!"),
            floatArrayOf(0f,0.3f,0.6f,0.9f),1000)
        assertEquals(listOf(" Привет",","," мир","!"),tokens.map { it.text })
        assertEquals(listOf("Привет,","мир!"),words.map { it.text })
        assertEquals(listOf(0L,600L),words.map { it.startMs })
        assertEquals(listOf(600L,940L),words.map { it.endMs })
        assertEquals(text,tokens.joinToString("") { it.text }.trim())
    }
    @Test fun `missing times remain unavailable instead of spread guesses`() {
        val (tokens,words)=GigaTokenAlignment.align("Да.",arrayOf("Да","."),floatArrayOf(),1000)
        assertTrue(words.isEmpty());assertTrue(tokens.all { it.startMs==null && it.endMs==null })
    }
    @Test fun `sentencepiece boundaries affect only derived words not native tokens`() {
        val pieces=arrayOf("▁При","вет",",","▁мир","!")
        val (tokens,words)=GigaTokenAlignment.align("Привет, мир!",pieces,floatArrayOf(0f,0.2f,0.3f,0.6f,0.9f),1000)
        assertEquals(pieces.toList(),tokens.map { it.text })
        assertEquals(listOf("Привет,","мир!"),words.map { it.text })
    }
    @Test fun `invalid timing or lexical mismatch is rejected`() {
        for(times in listOf(floatArrayOf(0f),floatArrayOf(0f,Float.NaN),floatArrayOf(0.6f,0.2f),floatArrayOf(0f,2f))) {
            try {GigaTokenAlignment.align("Да.",arrayOf("Да","."),times,1000);fail("accepted invalid timing")} catch(expected:IllegalArgumentException) {}
        }
        try {GigaTokenAlignment.align("Да.",arrayOf("да","."),floatArrayOf(0f,0.2f),1000);fail("rewrote raw text")} catch(expected:IllegalArgumentException) {}
    }
    @Test fun `streaming denoise restores lost tail but never claims alignment`() = runBlocking {
        val dir=Files.createTempDirectory("gtcrn-provider").toFile()
        try {
            val original=dir.resolve("original.wav")
            PcmWave.Writer(original).use { it.write(FloatArray(32000) { 0.125f }) }
            val before=original.readBytes()
            var calls=0;var resets=0
            val fake=object:GtcrnDenoiserProvider.Processor {
                override fun reset() {resets++}
                override fun run(samples:FloatArray):FloatArray {calls++;assertTrue(samples.size<=16000);return samples.copyOf(samples.size-68)}
                override fun flush()=floatArrayOf()
                override fun close() {}
            }
            val output=dir.resolve("filtered.wav")
            GtcrnDenoiserProvider(fake).use {
                val audit=it.denoise(original,output,dir.resolve("audit.json"))
                assertEquals(32000L,audit.originalSamples);assertEquals(31864L,audit.filteredSamples)
                assertEquals(32000L,audit.outputSamples);assertEquals(136L,audit.tailPaddingSamples)
                assertFalse(audit.alignmentVerified)
                assertTrue(dir.resolve("audit.json").readText().contains("\"alignmentVerified\":false"))
            }
            assertEquals(2,calls);assertEquals(2,resets);assertArrayEquals(before,original.readBytes())
            assertTrue(PcmWave.readFloat(output,31864,136).all { it==0f })
        } finally {dir.deleteRecursively()}
    }
    @Test fun `nonoverlap selection keeps acoustic ids and caps evidence to eight seconds`() {
        val turns=listOf(SpeakerTurn(9,0,14000),SpeakerTurn(3,1000,2000),SpeakerTurn(3,14000,18000))
        val clips=EmotionSelection.select(turns,18000)
        assertEquals(listOf(9,3),clips.map { it.speakerId })
        assertEquals(listOf(2000L,14000L),clips.map { it.startMs })
        assertEquals(listOf(10000L,18000L),clips.map { it.endMs })
        assertTrue(EmotionSelection.select(listOf(SpeakerTurn(0,0,1500)),1500).isEmpty())
    }
    @Test fun `same voice overlaps remain eligible while distinct voices are excluded`() {
        val clips=EmotionSelection.select(listOf(SpeakerTurn(1,0,3000),SpeakerTurn(1,1000,5000)),5000)
        assertEquals(1,clips.size);assertEquals(0L,clips[0].startMs);assertEquals(5000L,clips[0].endMs)
        assertTrue(EmotionSelection.select(listOf(SpeakerTurn(1,0,5000),SpeakerTurn(2,0,5000)),5000).isEmpty())
    }
    @Test fun `emotion frontend silence agrees with official log clamp and bounded shapes`() {
        val features=EmotionFrontend.logMel(FloatArray(16000))
        assertEquals(64*101,features.size)
        features.forEach { assertEquals(ln(1e-9),it.toDouble(),1e-5) }
        try { EmotionFrontend.logMel(FloatArray(16000*9));fail("unbounded utterance") } catch(expected:IllegalArgumentException) {}
    }
}
