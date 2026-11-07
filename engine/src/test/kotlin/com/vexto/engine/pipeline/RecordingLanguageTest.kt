package com.vexto.engine.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLanguageTest {
    @Test fun russianAndBelarusianKeepTheCurrentPipelineAndWhisperFlag() {
        val russian = RecordingLanguage.fromDetection("ru", 16000)
        val belarusian = RecordingLanguage.fromDetection(" BE ", 32000)
        assertEquals(RecordingLanguage.SLAVIC, russian.route)
        assertEquals(RecordingLanguage.SLAVIC, belarusian.route)
        assertEquals("be", belarusian.code)
        assertEquals("ru", russian.whisperLanguage)
        assertEquals("ru", belarusian.whisperLanguage)
        assertTrue(russian.detected)
    }

    @Test fun otherLanguagesDisableTheSlavicRoute() {
        listOf("en", "de", "haw").forEach { code ->
            val language = RecordingLanguage.fromDetection(code, RecordingLanguage.WINDOW_SAMPLES)
            assertEquals(RecordingLanguage.WHISPER_ONLY, language.route)
            assertEquals(code, language.whisperLanguage)
        }
    }

    @Test fun failedDetectionKeepsRussian() {
        val missed = RecordingLanguage.undetected()
        assertEquals("ru", missed.code)
        assertEquals(RecordingLanguage.SLAVIC, missed.route)
        assertEquals("ru", missed.whisperLanguage)
        assertFalse(missed.detected)
    }

    @Test fun codesThatAreNotWhisperLanguagesAreRejected() {
        listOf("", "r", "ru-ru", "english", "ENGLISH").forEach { code ->
            val error = runCatching { RecordingLanguage.fromDetection(code, 1) }.exceptionOrNull()
            assertTrue(code, error is IllegalArgumentException)
        }
    }
}
