package com.vexto.engine.review

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(plan.chunks.map { "Dialogue excerpt:\n$it" }, prompts.take(3))
        assertEquals("Partial summaries:\n1. 1\n2. 2", prompts[3])
        assertEquals("Partial summaries:\n1. 4\n2. 3", prompts[4])
        assertEquals("5", output)
    }

    @Test fun `blank padding does not create a generation request`() {
        val plan = SummaryPlan("Start" + " ".repeat(4000) + "End")
        assertFalse(plan.chunks.any { it.isBlank() })
        assertTrue(plan.chunks.first().startsWith("Start"))
        assertTrue(plan.chunks.last().endsWith("End"))
    }
}
