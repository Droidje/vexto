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
        val opening = "Начало разговора:\n" + "а".repeat(120) + "\n\nСодержание частей:"
        assertEquals(plan.chunks.map { "$it\n\n$question" }, prompts.take(3))
        val mergeQuestion = "Какова общая тема и что выяснилось?"
        assertEquals("$opening\n1\n\n2\n\n$mergeQuestion", prompts[3])
        assertEquals("$opening\n4\n\n3\n\n$mergeQuestion", prompts[4])
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
}
