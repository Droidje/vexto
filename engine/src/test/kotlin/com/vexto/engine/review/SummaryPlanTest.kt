package com.vexto.engine.review

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPlanTest {
    @Test fun `chunks preserve multilingual transcript and do not split a code point`() {
        val transcript = "я".repeat(649) + "🙂\n" + "Speaker 2: ещё текст\n".repeat(220) + "Последняя реплика"
        val plan = SummaryPlan(transcript)
        assertEquals(transcript, plan.chunks.joinToString(""))
        assertTrue(plan.chunks[1].startsWith("🙂"))
        assertTrue(plan.chunks.all { it.toByteArray().size <= 1300 })
    }

    @Test fun `odd tree uses every excerpt and progress includes all merges`() = runBlocking {
        val plan = SummaryPlan("а".repeat(1550) + "конец")
        assertEquals(3, plan.chunks.size)
        val prompts = mutableListOf<String>()
        val output = plan.run { input ->
            prompts += input
            prompts.size.toString()
        }
        assertEquals(plan.steps, prompts.size)
        val question = "Краткий смысл всей истории:"
        assertEquals(plan.chunks.map { "$it\n\n$question" }, prompts.take(3))
        val mergeQuestion = "Итог с учётом поздних исправлений:"
        assertEquals("1:\n1\n\n2:\n2\n\n$mergeQuestion", prompts[3])
        assertEquals("1:\n4\n\n2:\n3\n\n$mergeQuestion", prompts[4])
        assertTrue(prompts.all { it.toByteArray().size <= 1400 })
        assertEquals("5", output)
    }

    @Test fun `UI speaker labels stay out of prompts`() {
        val plan = SummaryPlan("Участник 1: Собирает груши.\nУчастник 2: Забрал корзину.")
        assertEquals("Собирает груши.\nЗабрал корзину.", plan.chunks.single())
    }

    @Test fun `near the output cap the incomplete last sentence is omitted`() {
        val text = "Выставка состоится. " + "а".repeat(294)
        assertEquals("Выставка состоится.", SummaryPlan.finishSummary(text))
        assertEquals("Краткий итог", SummaryPlan.finishSummary("Краткий итог"))
    }

    @Test fun `blank padding does not create a generation request`() {
        val plan = SummaryPlan("Start" + " ".repeat(4000) + "End")
        assertFalse(plan.chunks.any { it.isBlank() })
        assertTrue(plan.chunks.first().startsWith("Start"))
        assertTrue(plan.chunks.last().endsWith("End"))
    }

    @Test fun `failed merge propagates and a retry regenerates every source chunk`() = runBlocking {
        val plan = SummaryPlan("Начало обсуждения. " + "а".repeat(1550) + " Важный итог.")
        val failure = java.io.IOException("Synthetic native failure")
        val failedPrompts = mutableListOf<String>()
        try {
            plan.run { input ->
                failedPrompts += input
                if (failedPrompts.size > plan.chunks.size) throw failure
                "Краткий итог части."
            }
            throw AssertionError("A failed model request must not publish a partial summary")
        } catch (actual: java.io.IOException) {
            assertSame(failure, actual)
        }
        val retriedPrompts = mutableListOf<String>()
        val result = plan.run { input ->
            retriedPrompts += input
            "Итог ${retriedPrompts.size}."
        }
        assertEquals(failedPrompts.take(plan.chunks.size), retriedPrompts.take(plan.chunks.size))
        assertEquals(plan.steps, retriedPrompts.size)
        assertEquals("Итог ${plan.steps}.", result)
        assertTrue(retriedPrompts.all { it.toByteArray(Charsets.UTF_8).size <= 1400 })
    }

    @Test fun `merges retain complete outcomes at the end of both maximum size responses`() = runBlocking {
        for (text in listOf("я".repeat(1000), "x".repeat(2000))) {
            val plan = SummaryPlan(text)
            val ending = "Release cancelled. No new date."
            val response = "a".repeat(SummaryPlan.OutputBytes - ending.toByteArray().size) + ending
            var calls = 0
            plan.run { input ->
                if (calls++ >= plan.chunks.size) {
                    assertTrue(input.toByteArray(Charsets.UTF_8).size <= 1400)
                    assertEquals(2, Regex(Regex.escape(ending)).findAll(input).count())
                    assertTrue(input.startsWith("1:\n$response\n\n2:\n$response\n\n"))
                    assertFalse(input.contains("Начало разговора:"))
                }
                response
            }
        }
    }
}
