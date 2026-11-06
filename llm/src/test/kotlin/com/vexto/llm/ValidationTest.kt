package com.vexto.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    private val text = "всем привет меня зовут Мурзик\nбарсика попросим показать макеты\nпривет, ребята"

    @Test
    fun nameMustOccurInText() {
        assertTrue(Validation.isNameInText("Мурзик", text))
        assertTrue("окончание после имени допустимо", Validation.isNameInText("Барсик", text))
        assertFalse(Validation.isNameInText("Васька", text))
        assertFalse("приветствие не имя", Validation.isNameInText("Привет", text))
        assertFalse("часть другого слова не считается", Validation.isNameInText("Рзик", text))
    }

    @Test
    fun speakerNamesDropsHallucinationsAndDuplicates() {
        val raw = """{"speaker_1": "Мурзик", "speaker_2": "Мурзик", "speaker_3": "Васька"}"""
        val names = Validation.speakerNames(raw, listOf("speaker_1", "speaker_2", "speaker_3"), text)
        assertEquals(mapOf("speaker_1" to "Мурзик", "speaker_2" to null, "speaker_3" to null), names)
    }

    @Test
    fun speakerNamesSurvivesBrokenJson() {
        val names = Validation.speakerNames("{\"speaker_1\": ", listOf("speaker_1"), text)
        assertEquals(mapOf("speaker_1" to null), names)
    }

    @Test
    fun summaryKeepsOnlyKnownTimestamps() {
        val raw = """{"summary": "Обсудили сайт.", "points": [
            {"t": "00:40", "text": "Публикация в понедельник"},
            {"t": "09:99", "text": "Выдуманный момент"},
            {"t": "00:05", "text": "Макеты готовы"}]}"""
        val s = Validation.summary(raw, mapOf("00:05" to 5_000L, "00:40" to 40_200L))!!
        assertEquals("Обсудили сайт.", s.text)
        assertEquals(listOf("00:05", "00:40"), s.points.map { it.timestamp })
        assertEquals(40_200L, s.points[1].startMs)
    }

    @Test
    fun titleIsCleaned() {
        assertEquals("Спринт по портфолио", Validation.title("  «спринт по портфолио».\nлишнее"))
        assertNull(Validation.title(" . "))
    }
}
