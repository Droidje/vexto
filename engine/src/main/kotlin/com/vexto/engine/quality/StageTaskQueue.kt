package com.vexto.engine.quality

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/** Bounded task/result queues. Each worker owns one model; only the collector writes checkpoints. */
internal object StageTaskQueue {
    private data class Completion<R>(val chunk: AudioChunk, val result: Result<R>)

    suspend fun <W : Closeable, R> run(
        tasks: List<AudioChunk>,
        workers: Int,
        stopOnFailure: Boolean = false,
        open: suspend (Int) -> W,
        process: suspend (W, AudioChunk) -> R,
        collect: suspend (AudioChunk, Result<R>) -> Unit,
    ) {
        require(workers in 1..2 && tasks.map { it.id }.distinct().size == tasks.size)
        if (tasks.isEmpty()) return
        runStream(tasks.asFlow(), minOf(workers, tasks.size), stopOnFailure, open, process, collect)
    }

    suspend fun <W : Closeable, R> runStream(
        tasks: Flow<AudioChunk>, workers: Int, stopOnFailure: Boolean = false,
        open: suspend (Int) -> W, process: suspend (W, AudioChunk) -> R,
        collect: suspend (AudioChunk, Result<R>) -> Unit,
    ) = coroutineScope {
        require(workers in 1..2)
        val count = workers
        val pending = Channel<AudioChunk>(count)
        val completed = Channel<Completion<R>>(count)
        launch {
            try {
                val seen = mutableSetOf<String>()
                tasks.collect { require(seen.add(it.id)); pending.send(it) }
            }
            finally { pending.close() }
        }
        val pool = List(count) { index ->
            launch(Dispatchers.IO) {
                open(index).use { worker ->
                    for (chunk in pending) {
                        coroutineContext.ensureActive()
                        val result = try { Result.success(process(worker, chunk)) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { Result.failure(failure) }
                        completed.send(Completion(chunk, result))
                        if (stopOnFailure && result.isFailure) break
                    }
                }
            }
        }
        launch {
            try { pool.forEach { it.join() } }
            finally { completed.close() }
        }
        for (item in completed) {
            collect(item.chunk, item.result)
            // Persist the failed task before cancelling peers. A dead session must never drain the queue.
            if (stopOnFailure) item.result.getOrThrow()
        }
    }
}
