package com.vexto.engine.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAssemblerTest {

    private val assembler = TranscriptAssembler()

    @Test
    fun `words split into utterances at speaker change`() {
        val words = listOf(
            Word("добрый", 0, 400), Word("день", 450, 800),
            Word("привет", 1200, 1600),
            Word("как", 2000, 2200), Word("дела", 2250, 2600),
        )
        val turns = listOf(SpeakerTurn(7, 0, 900), SpeakerTurn(3, 1100, 1700), SpeakerTurn(7, 1900, 2700))

        val result = assembler.assemble(words, turns)

        assertEquals(listOf("добрый день", "привет", "как дела"), result.utterances.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), result.utterances.map { it.speakerId })
        assertEquals(listOf(0L, 1200L, 2000L), result.utterances.map { it.startMs })
        assertEquals(listOf(800L, 1600L, 2600L), result.utterances.map { it.endMs })
        assertEquals(listOf("speaker_1", "speaker_2"), result.speakers.map { it.id })
    }

    @Test
    fun `word goes to speaker with larger overlap`() {
        val words = listOf(Word("нет", 0, 500), Word("да", 900, 1300))
        val turns = listOf(SpeakerTurn(0, 0, 1000), SpeakerTurn(1, 1000, 2000))

        val result = assembler.assemble(words, turns)

        assertEquals(listOf("speaker_1", "speaker_2"), result.utterances.map { it.speakerId })
    }

    @Test
    fun `short interjection inside another speaker turn is kept`() {
        val words = listOf(Word("я", 0, 300), Word("думаю", 350, 800), Word("угу", 900, 1100), Word("что", 1200, 1500))
        val turns = listOf(SpeakerTurn(0, 0, 1600), SpeakerTurn(1, 880, 1120))

        val result = assembler.assemble(words, turns)

        assertEquals(listOf("я думаю", "угу", "что"), result.utterances.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), result.utterances.map { it.speakerId })
    }

    @Test
    fun `word outside turns snaps to nearest turn`() {
        val words = listOf(Word("раз", 0, 300), Word("два", 5000, 5300))
        val turns = listOf(SpeakerTurn(0, 0, 400), SpeakerTurn(1, 5500, 6000))

        val result = assembler.assemble(words, turns)

        assertEquals(listOf("speaker_1", "speaker_2"), result.utterances.map { it.speakerId })
    }

    @Test
    fun `long pause splits same speaker`() {
        val words = listOf(Word("раз", 0, 300), Word("два", 10_000, 10_300))
        val turns = listOf(SpeakerTurn(0, 0, 11_000))

        assertEquals(2, assembler.assemble(words, turns).utterances.size)
    }

    @Test
    fun `no turns gives single speaker`() {
        val result = assembler.assemble(listOf(Word("тест", 0, 100)), emptyList())
        assertEquals("speaker_1", result.utterances.single().speakerId)
    }
}
