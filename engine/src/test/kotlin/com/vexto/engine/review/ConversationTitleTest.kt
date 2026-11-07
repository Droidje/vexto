package com.vexto.engine.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitleTest {
    @Test fun `short dialogue reaches the model in full`() {
        val dialogue = "Подготовим выставку фотографий\nЗал уже забронировали."
        assertEquals("$dialogue\n\nНазвание разговора:", ConversationTitle.input(SummaryPlan(dialogue)))
    }

    @Test fun `long dialogue samples beginning middle and ending within native prompt limit`() {
        val dialogue = "начало" + "🙂".repeat(322) + "середина" + "я".repeat(644) + "окончание"
        val input = ConversationTitle.input(SummaryPlan(dialogue))
        assertTrue(input.toByteArray().size <= 1400)
        assertTrue("начало" in input && "середина" in input && "окончание" in input)
        assertTrue(input.none { it == '�' })
    }

    @Test fun `accepts concrete titles and rejects defaults or extra content`() {
        assertEquals("Фотовыставка в библиотеке", ConversationTitle.normalize("«Фотовыставка в библиотеке»"))
        assertEquals("Photo exhibition", ConversationTitle.normalize("Photo exhibition."))
        for (invalid in listOf("Разговор №4", "Chat", "Title: выставка", "Выставка\nЯ придумал название", "😀", "a".repeat(81))) {
            assertTrue(invalid, runCatching { ConversationTitle.normalize(invalid) }.isFailure)
        }
    }
}
