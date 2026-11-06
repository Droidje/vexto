package com.vexto.engine.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenWordsTest {

    @Test
    fun `bpe tokens with punctuation become words`() {
        val tokens = arrayOf("▁Добр", "ый", "▁день", "!", "▁Как", "▁дела", "?")
        val times = floatArrayOf(0.0f, 0.2f, 0.5f, 0.9f, 1.5f, 1.8f, 2.1f)

        val words = TokenWords.group(tokens, times, 1000, 4000)

        assertEquals(listOf("Добрый", "день!", "Как", "дела?"), words.map { it.text })
        assertEquals(listOf(1000L, 1500L, 2500L, 2800L), words.map { it.startMs })
        assertEquals(listOf(1500L, 2300L, 2800L, 3500L), words.map { it.endMs })
    }

    @Test
    fun `char tokens split on spaces`() {
        val tokens = arrayOf("д", "а", " ", "н", "е", "т")
        val times = floatArrayOf(0.0f, 0.04f, 0.08f, 0.3f, 0.34f, 0.38f)

        assertEquals(listOf("да", "нет"), TokenWords.group(tokens, times, 0, 1000).map { it.text })
    }

    @Test
    fun `spread covers segment`() {
        val words = TokenWords.spread("раз два", 0, 800)
        assertEquals(listOf(Word("раз", 0, 400), Word("два", 400, 800)), words)
    }
}
