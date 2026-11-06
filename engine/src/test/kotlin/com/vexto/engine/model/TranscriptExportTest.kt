package com.vexto.engine.model

import com.vexto.engine.model.export.TranscriptExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
        val document = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("transcript"), document.keys)
        document.getValue("transcript").jsonArray.forEach { element ->
            assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), element.jsonObject.keys)
            assertFalse(element.jsonObject.getValue("start_ms").jsonPrimitive.isString)
        }
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
        val strict = TranscriptExport.json(updated)
        assertFalse(strict.contains("emotion_probabilities"))
        assertSame(scores, updated.utterances.first().emotionProbabilities)
        val parsed = TranscriptExport.parseJson(TranscriptExport.jsonWithMetadata(updated))
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

    @Test
    fun `json preserves original whitespace punctuation and unicode`() {
        val raw = "  Привет, \"мир\"!\t\nЕщё\u00a0строка.\r\n  "
        val original = Transcript(listOf(Utterance("speaker_1", 0, 2000, raw)), listOf(Speaker("speaker_1")))
        val parsed = TranscriptExport.parseJson(TranscriptExport.json(original))
        assertEquals(raw, parsed.single().text)
        assertEquals(raw, original.utterances.single().text)
        assertEquals(emptyList<com.vexto.engine.model.export.JsonUtterance>(), TranscriptExport.parseJson(TranscriptExport.json(Transcript.Empty)))
    }

    @Test
    fun `json rejects invalid bounds and unordered rows without sorting text`() {
        for ((start, end) in listOf(-1L to 10L, 10L to 10L, 10L to 9L)) {
            val invalid = transcript.copy(utterances = listOf(Utterance("speaker_1", start, end, "исходный текст")))
            assertThrows(IllegalArgumentException::class.java) { TranscriptExport.json(invalid) }
        }
        val unordered = transcript.copy(utterances = listOf(Utterance("speaker_1", 100, 200, "первый"), Utterance("speaker_2", 0, 50, "второй")))
        assertThrows(IllegalArgumentException::class.java) { TranscriptExport.json(unordered) }
        assertEquals(listOf("первый", "второй"), unordered.utterances.map { it.text })
    }

    @Test
    fun `ordered starts permit overlapping speakers and decreasing ends`() {
        val overlapping = transcript.copy(utterances = listOf(Utterance("speaker_1", 0, 1000, "длинная реплика"), Utterance("speaker_2", 100, 200, "перебивание")))
        val parsed = TranscriptExport.parseJson(TranscriptExport.json(overlapping))
        assertEquals(listOf(0L, 100L), parsed.map { it.startMs })
        assertEquals(listOf(1000L, 200L), parsed.map { it.endMs })
    }

    @Test
    fun `json import requires integer values rather than strings or fractions`() {
        for (start in listOf("\"0\"", "0.5", "-1", "true", "1e100")) {
            val input = """{"transcript":[{"speaker_id":"speaker_1","start_ms":$start,"end_ms":1000,"text":"текст"}]}"""
            assertThrows(IllegalArgumentException::class.java) { TranscriptExport.parseJson(input) }
        }
    }

}
