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

    private val dialog = listOf(
        Utterance("speaker_1", 0, 1000, "Всем привет, меня зовут Мурзик."),
        Utterance("speaker_1", 1000, 2000, "Барсик, покажешь макеты?"),
        Utterance("speaker_2", 2000, 3000, "Да, к четвергу."),
        Utterance("speaker_3", 3000, 4000, "Я пришлю отзывы Барсику вечером."),
    )

    @Test
    fun speakerNamesKeepsConfirmedNames() {
        val raw = """```json
            [{"name": "Мурзик", "speaker": "speaker_1"},
             {"name": "Барсик", "speaker": "speaker_2"},
             {"name": "Светлана", "speaker": "speaker_3"},
             {"name": "Барсику", "speaker": "speaker_3"}]
            ```"""
        val names = Validation.speakerNames(raw, dialog)
        assertEquals(mapOf("speaker_1" to "Мурзик", "speaker_2" to "Барсик", "speaker_3" to null), names)
    }

    @Test
    fun speakerNamesRejectsOwnVocative() {
        // speaker_1 обращается к Барсику, это не его имя.
        val names = Validation.speakerNames("""[{"name": "Барсик", "speaker": "speaker_1"}]""", dialog)
        assertNull(names["speaker_1"])
    }

    @Test
    fun speakerNamesSurvivesBrokenJson() {
        val names = Validation.speakerNames("[{\"name\": ", dialog)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3"), names.keys.toList())
        assertTrue(names.values.all { it == null })
    }

    @Test
    fun summaryKeepsOnlyKnownTimestamps() {
        val raw = """{"summary": "Обсудили сайт.", "points": [
            {"t": "00:40", "text": "Публикация в понедельник"},
            {"t": "09:99", "text": "Выдуманный момент"},
            {"t": "00:05", "text": "Speaker_2: Макеты готовы"}]}"""
        val s = Validation.summary(raw, mapOf("00:05" to 5_000L, "00:40" to 40_200L))!!
        assertEquals("Обсудили сайт.", s.text)
        assertEquals(listOf("00:05", "00:40"), s.points.map { it.timestamp })
        assertEquals("Макеты готовы", s.points[0].text)
        assertEquals(40_200L, s.points[1].startMs)
    }

    @Test
    fun titleIsCleaned() {
        assertEquals("Спринт по портфолио", Validation.title("  «спринт по портфолио».\nлишнее"))
        assertNull(Validation.title(" . "))
    }
}
