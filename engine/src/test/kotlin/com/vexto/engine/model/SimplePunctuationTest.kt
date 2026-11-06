package com.vexto.engine.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SimplePunctuationTest {

    @Test
    fun `capitalizes and closes sentence`() {
        assertEquals("Добрый день.", SimplePunctuation.format("добрый день"))
        assertEquals("Правда?", SimplePunctuation.format("правда?"))
        assertEquals("", SimplePunctuation.format("  "))
    }
}
