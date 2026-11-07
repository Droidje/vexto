package com.vexto.engine.punctuation

import org.junit.Assert.assertEquals
import org.junit.Test

class RuPunctTextTest {
    private val pieces = WordPiece(
        mapOf(
            "[CLS]" to 2, "[SEP]" to 3, "[UNK]" to 1,
            "привет" to 10, "мир" to 11, "длинное" to 12,
        ),
        "##",
        8,
    )

    @Test
    fun `labels follow the published RUPunct order`() {
        assertEquals(33, RuPunctText.labels.size)
        assertEquals("UPPER_COMMA", RuPunctText.labels[3])
        assertEquals("LOWER_PERIOD", RuPunctText.labels[1])
        assertEquals("LOWER_O", RuPunctText.labels[31])
        assertEquals("UPPER_TIRE", RuPunctText.labels[9])
    }

    @Test
    fun `render keeps the word and adds the predicted mark`() {
        assertEquals("Привет,", RuPunctText.render("привет", "UPPER_COMMA"))
        assertEquals("мир.", RuPunctText.render("мир", "LOWER_PERIOD"))
        assertEquals("Привет —", RuPunctText.render("привет", "UPPER_TIRE"))
        assertEquals("привет—", RuPunctText.render("привет", "LOWER_TIRE"))
        assertEquals("ПРИВЕТ", RuPunctText.render("привет", "UPPER_TOTAL_O"))
    }

    @Test
    fun `restore uses the first piece of each word`() {
        val calls = mutableListOf<Int>()
        val text = RuPunctText.restore("Привет, мир!", pieces) { ids ->
            calls += ids.size
            IntArray(ids.size) { index ->
                when (ids[index]) {
                    10 -> 3
                    11 -> 1
                    else -> 31
                }
            }
        }
        assertEquals("Привет, мир.", text)
        assertEquals(listOf(4), calls)
    }

    @Test
    fun `long words and chunks stay inside the piece budget`() {
        val calls = mutableListOf<Int>()
        val text = RuPunctText.restore("привет мир", pieces, maxPieces = 1) { ids ->
            calls += ids.size
            IntArray(ids.size) { 31 }
        }
        assertEquals("привет мир", text)
        assertEquals(listOf(3, 3), calls)
    }

    @Test
    fun `a word longer than the tokenizer limit stays unknown without new letters`() {
        assertEquals(intArrayOf(1).toList(), pieces.encodeWord("слишкомдлинное").toList())
        val text = RuPunctText.restore("Ёж", pieces) { ids -> IntArray(ids.size) { 31 } }
        assertEquals("ёж", text)
    }
}
