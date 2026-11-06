package com.vexto.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTextTest {

    @Test
    fun formatsLines() {
        val u = Utterance("speaker_2", 754_300, 760_000, " Привет ")
        assertEquals("[12:34] speaker_2: Привет", TranscriptText.line(u))
        assertEquals("125:00", TranscriptText.timestamp(7_500_000))
    }

    @Test
    fun chunksByTokenBudget() {
        val us = (0 until 5).map { Utterance("speaker_1", it * 1000L, it * 1000L + 900, "t$it") }
        val chunks = TranscriptText.chunk(us, budgetTokens = 10, counts = intArrayOf(4, 4, 4, 20, 1))
        assertEquals(listOf(2, 1, 1, 1), chunks.map { it.size })
    }
}
