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
