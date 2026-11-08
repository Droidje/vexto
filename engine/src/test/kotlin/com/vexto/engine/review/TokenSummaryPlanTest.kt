package com.vexto.engine.review

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TokenSummaryPlanTest {
    @Test fun `packs more than two results and preserves every source character`() = runBlocking {
        val source = "данные🙂 ".repeat(100)
        val pieces = mutableListOf<String>()
        val generated = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        TokenSummaryPlan(source).run(
            fitPrefix = { input, _ -> SummaryPlan.prefix(input, 100).also { pieces += it }.toByteArray().size },
            fits = { it.length <= 1000 },
            summarize = { generated += it; "Краткий факт." },
            progress = { done, total -> progress += done to total },
        )
        assertEquals(source.trim(), pieces.joinToString(""))
        assertTrue(generated.last().contains("3:\n"))
        assertEquals(pieces.size + 1, generated.size)
        assertEquals(generated.size to generated.size, progress.last())
    }

    @Test fun `descending merge search handles nonmonotone token counts`() = runBlocking {
        val inputs = mutableListOf<String>()
        TokenSummaryPlan("a".repeat(40)).run(
            fitPrefix = { input, _ -> input.take(10).toByteArray().size },
            fits = { candidate -> candidate.contains("4:\n") || !candidate.contains("3:\n") },
            summarize = { inputs += it; "fact" }, progress = { _, _ -> },
        )
        assertEquals(5, inputs.size)
        assertTrue(inputs.last().contains("4:\n"))
    }

    @Test fun `completed responses survive failure and reopen but source revisions cannot reuse them`() = runBlocking {
        val root = Files.createTempDirectory("summary-checkpoints").toFile()
        try {
            val cache = SummaryCheckpoints(root, "source+model+prompt-v1")
            assertEquals("done", cache.response("first") { "done" })
            try { cache.response("second") { error("injected failure") }; fail() } catch (_: IllegalStateException) { }
            val reopened = SummaryCheckpoints(root, "source+model+prompt-v1")
            assertEquals("done", reopened.response("first") { error("must not regenerate") })
            assertEquals("retried", reopened.response("second") { "retried" })
            assertEquals("new", SummaryCheckpoints(root, "source+model+prompt-v2").response("first") { "new" })
        } finally { root.deleteRecursively() }
    }
}
