package com.vexto.engine.quality

import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.ImmutableAsrCandidate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdaptiveRefinementTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun agreementSkipsHeavyButNumbersAndOverlapEscalate() {
        val chunk = AudioChunk("chunk_000000", 0, 160000, "chunks/chunk_000000.wav", "a".repeat(64))
        val plan = ChunkPlan(160000, listOf(chunk))
        fun candidate(source: AsrSource, text: String) = ImmutableAsrCandidate.create(chunk.id, source,
            chunk.inputSha256, text, emptyList(), emptyList(), tmp.newFile().also { it.delete() })
        val g = mapOf(chunk.id to candidate(AsrSource.GIGAAM, "Заплатить 100 рублей завтра."))
        val same = mapOf(chunk.id to candidate(AsrSource.WHISPER, "Заплатить 100 рублей завтра."))
        assertTrue(AdaptiveRefinement.plan(plan, g, same, emptyList(), true).ids.isEmpty())
        val other = mapOf(chunk.id to candidate(AsrSource.WHISPER, "Заплатить 200 рублей завтра."))
        assertTrue(AdaptiveRefinement.plan(plan, g, other, emptyList(), true).chunks.getValue(chunk.id).contains("number_disagreement"))
        val overlap = listOf(PipelineTurn(0, 0, 5000), PipelineTurn(1, 3000, 7000))
        assertTrue(AdaptiveRefinement.plan(plan, g, same, overlap, true).chunks.getValue(chunk.id).contains("overlapping_speech"))
    }

    @Test fun cpuBudgetWaitsAndCancelledWaiterDoesNotOwnASlot() = runBlocking {
        val whole = InferenceResources.acquire(InferenceResources.Cost(4, 1_000_000))
        val waiter = async { InferenceResources.acquire(InferenceResources.Cost(2, 1_000_000)) }
        delay(60); assertFalse(waiter.isCompleted)
        waiter.cancelAndJoin(); InferenceResources.release(whole)
        val a = withTimeout(1000) { InferenceResources.acquire(InferenceResources.Cost(2, 1_000_000)) }
        val b = withTimeout(1000) { InferenceResources.acquire(InferenceResources.Cost(2, 1_000_000)) }
        InferenceResources.release(a); InferenceResources.release(b)
    }
}
