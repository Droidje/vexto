package com.vexto.engine.model

import com.vexto.engine.model.export.TranscriptExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExportTest {

    private val transcript = Transcript(
        utterances = listOf(
            Utterance("speaker_1", 1250, 4380, "Добрый день!"),
            Utterance("speaker_2", 4500, 61_000, "Здравствуйте."),
        ),
        speakers = listOf(Speaker("speaker_1", "Мурзик"), Speaker("speaker_2")),
    )

    @Test
    fun `json matches customer schema`() {
        val json = TranscriptExport.json(transcript)

        assertTrue(json.startsWith("{\n  \"transcript\": ["))
        assertTrue(json.contains("\"speaker_id\": \"speaker_1\""))
        assertTrue(json.contains("\"start_ms\": 1250"))
        assertTrue(json.contains("\"end_ms\": 4380"))
        assertTrue(json.contains("\"text\": \"Добрый день!\""))
        val parsed = TranscriptExport.parseJson(json)
        assertEquals(2, parsed.size)
        assertEquals("speaker_2", parsed[1].speakerId)
    }

    @Test
    fun `json keeps speaker ids even when renamed`() {
        assertTrue(!TranscriptExport.json(transcript).contains("Мурзик"))
    }

    @Test
    fun `gigaam scores are exported in design order without adding scores to legacy lines`() {
        val scores = EmotionProbabilities.fromModel(floatArrayOf(0.125f, 0f, 0.25f, 0.625f))
        assertEquals(Emotion.Joy, scores.strongest())
        val updated = transcript.copy(utterances = listOf(
            transcript.utterances.first().copy(emotionProbabilities = scores), transcript.utterances.last(),
        ))
        val parsed = TranscriptExport.parseJson(TranscriptExport.json(updated))
        assertEquals(listOf(0.25, 0.625, 0.0, 0.125), parsed[0].emotionProbabilities)
        assertEquals(null, parsed[1].emotionProbabilities)
    }

    @Test
    fun `text uses names and clock`() {
        assertEquals(
            "[00:01] Мурзик: Добрый день!\n\n[00:04] Спикер 2: Здравствуйте.",
            TranscriptExport.text(transcript),
        )
    }

    @Test
    fun `srt timing format`() {
        assertTrue(TranscriptExport.srt(transcript).contains("00:00:04,500 --> 00:01:01,000"))
    }
}
