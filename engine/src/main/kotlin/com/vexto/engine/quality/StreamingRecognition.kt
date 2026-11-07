package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
internal data class StreamingChunks(val sourceSha256: String, val plan: ChunkPlan,
                                    val complete: Boolean = false, val aborted: Boolean = false)

/** Append-only published geometry. The final full plan must have exactly the same prefix. */
internal class StreamingChunkPublisher(private val job: File, private val input: File) {
    private val sourceHash = JobFiles.sha256(input)
    private var published = emptyList<AudioChunk>()
    private var frontier = -1L
    val snapshot = File(job, "streaming-chunks.json")

    suspend fun advance(progress: DiarizationProgress): ChunkPlan? {
        if (progress.stableMs <= frontier) return null
        frontier = progress.stableMs
        val all = ChunkPlan.create(input, job, progress.turns, sourceHash, published, publish = false)
        // Leave two seconds beyond the mature end, in addition to the native segmentation collar.
        val ready = all.chunks.takeWhile { it.toSample <= (progress.stableMs - 2000).coerceAtLeast(0) * 16 }
        require(ready.take(published.size) == published) { "Diarization revised a published chunk" }
        if (ready.size == published.size) return null
        published = ready
        val plan = subset(all.sampleCount, ready)
        JobFiles.save(File(job, "chunk-storage.json"), ChunkStorage(
            inputWav = input.relativeTo(job).invariantSeparatorsPath, inputSha256 = sourceHash))
        JobFiles.save(snapshot, StreamingChunks(sourceHash, plan))
        return plan
    }

    fun finish(final: ChunkPlan, success: Boolean) {
        val matches = final.chunks.take(published.size) == published
        JobFiles.save(snapshot, StreamingChunks(sourceHash, final, complete = true, aborted = !success || !matches))
    }

    private fun subset(count: Long, chunks: List<AudioChunk>): ChunkPlan {
        val omitted = mutableListOf<SampleRange>()
        var at = 0L
        for (chunk in chunks) {
            if (chunk.fromSample > at) omitted += SampleRange(at, chunk.fromSample)
            at = chunk.toSample
        }
        if (at < count) omitted += SampleRange(at, count)
        return ChunkPlan(count, chunks, omitted).also { it.validate() }
    }
}

/** Starts ASR before long-file diarization ends, without independent diarizer sessions. */
internal object StreamingRecognition {
    suspend fun run(job: File, input: File, diarization: Deferred<WorkerResult>, slavic: Boolean,
                    recognize: suspend (WorkerStage, ChunkPlan) -> WorkerResult): Map<WorkerStage, WorkerResult> {
        val publisher = StreamingChunkPublisher(job, input)
        var first: ChunkPlan? = null
        while (!diarization.isCompleted && first == null) {
            val progress = runCatching { JobFiles.load<DiarizationProgress>(File(job, "diarization-progress.json")) }.getOrNull()
            if (progress != null) first = publisher.advance(progress)
            if (first == null) delay(100)
        }
        val initial = first ?: return emptyMap()
        return coroutineScope {
            val stages = listOfNotNull(WorkerStage.GIGAAM.takeIf { slavic }, WorkerStage.WHISPER)
            val jobs = stages.associateWith { kind -> async { recognize(kind, initial) } }
            try {
                while (!diarization.isCompleted) {
                    val progress = runCatching { JobFiles.load<DiarizationProgress>(File(job, "diarization-progress.json")) }.getOrNull()
                    if (progress != null) publisher.advance(progress)
                    delay(100)
                }
                val result = diarization.await()
                val final = ChunkPlan.create(input, job, result.turns.takeIf { result.completed && result.error == null })
                publisher.finish(final, result.completed && result.error == null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Close both queues explicitly; a normal full-plan retry will verify their partial RAW.
                val partial = JobFiles.load<StreamingChunks>(publisher.snapshot)
                JobFiles.save(publisher.snapshot, partial.copy(complete = true, aborted = true))
            }
            jobs.mapValues { it.value.await() }
        }
    }

    fun tasks(job: File, initial: ChunkPlan, known: Set<String>,
              update: (ChunkPlan) -> Unit) = flow {
        val source = JobFiles.load<ChunkStorage>(File(job, "chunk-storage.json")).inputSha256
        var previous = initial.chunks
        var emitted = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = JobFiles.load<StreamingChunks>(File(job, "streaming-chunks.json"))
            require(!next.aborted && next.sourceSha256 == source) { "Streaming plan aborted" }
            next.plan.validate()
            require(next.plan.chunks.take(previous.size) == previous) { "Published ASR prefix changed" }
            update(next.plan)
            while (emitted < next.plan.chunks.size) {
                val chunk = next.plan.chunks[emitted++]
                if (chunk.id !in known) emit(chunk)
            }
            previous = next.plan.chunks
            if (next.complete) break
            delay(100)
        }
    }
}
