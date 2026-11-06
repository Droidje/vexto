package com.vexto.engine.pipeline

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UtteranceEmotionAnnotationsTest {
    @Test fun `same speaker lines are scored separately without changing text words or original audio`() = runBlocking {
        val directory = Files.createTempDirectory("utterance-emotion").toFile()
        try {
            val wav = directory.resolve("original.wav")
            PcmWave.Writer(wav).use { it.write(FloatArray(6400)) }
            val before = wav.readBytes()
            val transcript = Transcript(listOf(
                Utterance("speaker_1", 0, 100, " Один! ", listOf(Word("Один!", 0, 100))),
                Utterance("speaker_1", 200, 400, "Два?", listOf(Word("Два?", 200, 400))),
            ), listOf(Speaker("speaker_1")))
            var calls = 0
            var checkpoint = emptyList<UtteranceEmotionEvidence>()
            val fake = object : EmotionProvider.Predictor {
                override val runtimeVersion = "test"
                override fun predict(features: FloatArray, frames: Int): FloatArray {
                    assertEquals(21, frames) // Both lines use their own >=200ms clip, no neighboring speech.
                    assertEquals(64 * frames, features.size)
                    return if (calls++ == 0) floatArrayOf(.1f, .1f, .7f, .1f) else floatArrayOf(.1f, .7f, .1f, .1f)
                }
                override fun close() {}
            }
            val evidence = EmotionProvider(fake).use { provider ->
                provider.annotateUtterances(wav, UtteranceEmotionAnnotations.targets(transcript)) { checkpoint = it }
            }
            assertEquals(2, calls)
            assertEquals(evidence, checkpoint)
            val annotated = UtteranceEmotionAnnotations.attach(transcript, evidence)
            assertEquals(Emotion.Neutral, annotated.utterances[0].emotionProbabilities!!.strongest())
            assertEquals(Emotion.Sadness, annotated.utterances[1].emotionProbabilities!!.strongest())
            assertEquals(transcript, annotated.copy(utterances = annotated.utterances.map { it.copy(emotionProbabilities = null) }))
            assertArrayEquals(before, wav.readBytes())
            val partial = UtteranceEmotionAnnotations.attach(transcript, evidence.take(1))
            assertNull(partial.utterances[1].emotionProbabilities)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `stale identity duplicate and invalid probability mapping are rejected atomically`() {
        val transcript = Transcript(listOf(Utterance("speaker_1", 100, 200, "Exact.")), listOf(Speaker("speaker_1")))
        val target = UtteranceEmotionAnnotations.targets(transcript).single()
        val evidence = UtteranceEmotionEvidence(target, listOf(.7, .1, .1, .1), 1)
        for (bad in listOf(listOf(evidence, evidence), listOf(evidence.copy(target = target.copy(endMs = 201))),
            listOf(evidence.copy(target = target.copy(speakerId = "speaker_2"))))) {
            try { UtteranceEmotionAnnotations.attach(transcript, bad); fail("accepted stale emotion target") }
            catch (expected: IllegalArgumentException) {}
        }
        try { evidence.copy(probabilities = listOf(.2, .2, .2, .2)); fail("accepted invalid probabilities") }
        catch (expected: IllegalArgumentException) {}
        assertNull(transcript.utterances.single().emotionProbabilities)
    }
}
