package com.vexto.engine.pipeline

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.*
import com.vexto.engine.models.OverlapManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OverlapProviderTest {
    @Test fun `one centered window never includes adjacent speech or pads short replies`() {
        assertEquals(emptyList<Long>(), OverlapWindows.starts(100, 4099).toList())
        assertEquals(listOf(1600L), OverlapWindows.starts(100, 4100).toList())
        assertEquals(listOf(9600L), OverlapWindows.starts(100, 5100).toList())
        assertEquals(listOf(33600L), OverlapWindows.starts(100, 8100).toList())
        assertEquals(listOf(41600L), OverlapWindows.starts(100, 9100).toList())
        assertEquals(listOf(1600L), OverlapWindows.starts(100, 4101).toList()) // Odd millisecond rounds inward.
        assertEquals(listOf(28_769_600L), OverlapWindows.starts(100, 3_600_100).toList())
        assertEquals(0, OverlapWindows.count(100, 4099))
        assertEquals(1, OverlapWindows.count(100, 3_600_100))
    }

    @Test fun `negative positive and short unknown remain distinct and preserve source`() = runBlocking {
        val directory = Files.createTempDirectory("overlap-provider").toFile()
        try {
            val wav = directory.resolve("original.wav")
            PcmWave.Writer(wav).use { it.write(FloatArray(16000 * 10) { index -> if (index < 16000 * 5) .2f else -.2f }) }
            val before = wav.readBytes()
            val transcript = Transcript(listOf(
                Utterance("speaker_1", 0, 4000, " Один! ", listOf(Word("Один!", 0, 4000))),
                Utterance("speaker_2", 4000, 5000, "Угу."),
                Utterance("speaker_1", 5000, 10000, "Два?"),
            ), listOf(Speaker("speaker_1"), Speaker("speaker_2")))
            var calls = 0
            var closed = false
            var checkpoint = emptyList<UtteranceOverlapEvidence>()
            val predictor = object : OverlapProvider.Predictor {
                override fun predict(audio: FloatArray): Float {
                    assertEquals(64000, audio.size)
                    assertTrue(audio.all { if (calls == 0) it > 0 else it < 0 })
                    return if (calls++ == 0) .91f else .95f
                }
                override fun close() { closed = true }
            }
            val evidence = OverlapProvider(predictor).use {
                it.annotate(wav, UtteranceOverlapAnnotations.targets(transcript), "model-sha:policy") { checkpoint = it }
            }
            assertEquals(2, calls)
            assertEquals(listOf(1, 0, 1), evidence.map { it.windows })
            assertTrue(closed)
            assertEquals(evidence, checkpoint)
            val annotated = UtteranceOverlapAnnotations.attach(transcript, evidence, "model-sha:policy")
            assertEquals(listOf(false, null, true), annotated.utterances.map { it.overlapDetected })
            assertEquals(transcript, annotated.copy(utterances = annotated.utterances.map { it.copy(overlapDetected = null) }))
            assertArrayEquals(before, wav.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `threshold is frozen and invalid evidence cannot be attached`() {
        val transcript = Transcript(listOf(Utterance("speaker_1", 100, 4100, "Exact.")), emptyList())
        val target = UtteranceOverlapAnnotations.targets(transcript).single()
        val evidence = UtteranceOverlapEvidence(target, OverlapManifest.Threshold, 1, "a")
        assertEquals(true, evidence.detected)
        assertEquals(false, evidence.copy(maxScore = OverlapManifest.Threshold - 1e-10).detected)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertThrows(IllegalArgumentException::class.java) { evidence.copy(maxScore = bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(windows = 0) }
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(maxScore = null) }
        assertThrows(IllegalArgumentException::class.java) {
            UtteranceOverlapAnnotations.attach(transcript, listOf(evidence, evidence), "a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UtteranceOverlapAnnotations.attach(transcript, listOf(evidence.copy(target = target.copy(speakerId = "speaker_2"))), "a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UtteranceOverlapAnnotations.attach(transcript, listOf(evidence), "other-model")
        }
        assertNull(transcript.utterances.single().overlapDetected)
    }

    @Test fun `invalid native score never yields a completed target checkpoint`() = runBlocking {
        val directory = Files.createTempDirectory("overlap-invalid").toFile()
        try {
            val wav = directory.resolve("original.wav")
            PcmWave.Writer(wav).use { it.write(FloatArray(64000)) }
            var checkpoints = 0
            val predictor = object : OverlapProvider.Predictor {
                override fun predict(audio: FloatArray) = Float.NaN
                override fun close() {}
            }
            try {
                OverlapProvider(predictor).use { it.annotate(wav,
                    listOf(UtteranceOverlapTarget(0, "s", 0, 4000)), "model") { checkpoints++ } }
                fail("accepted invalid score")
            } catch (expected: IllegalArgumentException) { }
            assertEquals(0, checkpoints)
        } finally { directory.deleteRecursively() }
    }
}
