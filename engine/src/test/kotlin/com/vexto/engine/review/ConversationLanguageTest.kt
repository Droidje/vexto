package com.vexto.engine.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLanguageTest {
    @Test fun `detects dialogue language independently of the Russian app labels`() {
        assertEquals(ConversationLanguage.Russian,
            ConversationLanguage.detect("Участник 1: Сегодня готовим выставку фотографий."))
        assertEquals(ConversationLanguage.Ukrainian,
            ConversationLanguage.detect("Участник 1: Це українська виставка. Її відкриють завтра."))
        assertEquals(ConversationLanguage.Belarusian,
            ConversationLanguage.detect("Участник 1: Мы падрыхтавалі выставу. Уваход вольны заўтра."))
        assertEquals(ConversationLanguage.Unspecified,
            ConversationLanguage.detect("Участник 1: Great!\nУчастник 2: OK."))
    }

    @Test fun `all instructions fit the packaged worker and explicitly tell Russian model what to output`() {
        for (language in ConversationLanguage.entries) {
            assertTrue(language.summaryPrompt().toByteArray().size <= 400)
            assertTrue(language.titlePrompt().toByteArray().size <= 400)
        }
        val russian = ConversationLanguage.Russian
        assertTrue(russian.summaryPrompt().contains("по-русски."))
        assertTrue(russian.titlePrompt().startsWith("Пиши по-русски."))
    }
}
