package com.vexto.engine.quality

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StageTaskQueueTest {
    private class Worker(private val closed: AtomicInteger) : Closeable {
        override fun close() { closed.incrementAndGet() }
    }
    private fun tasks() = List(8) { i ->
        AudioChunk("chunk_%06d".format(i), i.toLong(), i + 1L, "unused", "0".repeat(64))
    }

    @Test fun twoModelLifetimesServeAllChunksWithRealOverlap() = runBlocking {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        StageTaskQueue.run(tasks(), 2, open = { opened.incrementAndGet(); Worker(closed) },
            process = { _, chunk ->
                val concurrent = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                if (concurrent == 2) bothStarted.complete(Unit)
                bothStarted.await()
                delay(1)
                active.decrementAndGet()
                chunk.id
            }, collect = { _, result -> seen += result.getOrThrow() })
        assertEquals(2, opened.get())
        assertEquals(2, closed.get())
        assertEquals(2, peak.get())
        assertEquals(tasks().map { it.id }, seen.sorted())
    }

    @Test fun deadPersistentWorkerCheckpointsFailureAndStopsTakingTasks() = runBlocking {
        val closed = AtomicInteger()
        val collected = mutableListOf<String>()
        try {
            StageTaskQueue.run<Worker, Unit>(tasks(), 1, stopOnFailure = true,
                open = { Worker(closed) }, process = { _, _ -> error("worker dead") },
                collect = { chunk, result -> assertTrue(result.isFailure); collected += chunk.id })
            fail("Fatal worker failure must stop the stage")
        } catch (expected: IllegalStateException) {
            assertEquals("worker dead", expected.message)
        }
        assertEquals(listOf(tasks().first().id), collected)
        assertEquals(1, closed.get())
    }
}
