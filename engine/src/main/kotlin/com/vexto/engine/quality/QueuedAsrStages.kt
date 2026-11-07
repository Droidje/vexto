package com.vexto.engine.quality

import com.vexto.engine.models.QualityModels
import com.vexto.engine.pipeline.ComparisonChunk
import com.vexto.engine.pipeline.GigaAmCandidateProvider
import com.vexto.engine.pipeline.RecordingLanguage
import com.vexto.engine.pipeline.WhisperComparisonProvider
import com.vexto.engine.review.ImmutableAsrCandidate
import com.vexto.engine.review.LocalNativeExecutor
import com.vexto.engine.review.AsrSource
import com.vexto.engine.runtime.NativeProcessRunner
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** ASR stages remain sequential; bounded workers inside one stage share only immutable input metadata. */
internal object QueuedAsrStages {
    private class Recognizer(
        val generate: suspend (ComparisonChunk, File) -> ImmutableAsrCandidate,
        private val resource: Closeable,
    ) : Closeable {
        override fun close() = resource.close()
    }

    suspend fun run(
        request: WorkerRequest,
        job: File,
        input: File,
        models: QualityModels,
        runner: NativeProcessRunner,
        onProgress: (Float) -> Unit,
    ): WorkerResult {
        require(request.stage == WorkerStage.GIGAAM || request.stage == WorkerStage.WHISPER)
        val profile = ProcessingProfile.read(job)
        val plan = JobFiles.load<ChunkPlan>(File(job, "chunks.json")).also { it.validate() }
        val warnings = ConcurrentLinkedQueue<String>()
        val saved = if (request.resumeKey == null) null else
            JobFiles.load<WorkerResult>(request.stage.resultFile(job)).takeIf {
                it.requestId == request.requestId && it.stage == request.stage
            }
        val giga = request.stage == WorkerStage.GIGAAM
        val workers = if (giga) profile.gigaWorkers else profile.whisperWorkers
        val source = if (giga) "gigaam" else "whisper"
        val chunks = plan.chunks.associateBy { it.id }
        val expectedSource = if (giga) AsrSource.GIGAAM else AsrSource.WHISPER
        // Recheck artifacts at the worker boundary; a resume never trusts a path alone.
        val references = saved?.candidates.orEmpty().distinctBy { it.chunkId }.filter { ref ->
            val chunk = chunks[ref.chunkId]
            chunk != null && ref.source == source && runCatching {
                ImmutableAsrCandidate.load(JobFiles.inside(job, ref.file), ref.sha256)
                    .verifyIdentity(chunk.id, chunk.inputSha256, expectedSource)
            }.isSuccess
        }.toMutableList()
        val known = references.mapTo(mutableSetOf()) { it.chunkId }
        val pending = plan.chunks.filter { it.id !in known }
        var completed = known.size
        val peak = AtomicLong(saved?.peakCombinedRssBytes ?: 0L)

        fun result() = WorkerResult(request.stage, requestId = request.requestId,
            candidates = references.sortedBy { it.chunkId }, warnings = warnings.toList(),
            peakCombinedRssBytes = peak.get())

        if (completed > 0) onProgress(completed.toFloat() / plan.chunks.size.coerceAtLeast(1))
        if (pending.isEmpty()) {
            onProgress(1f)
            return result()
        }
        val waves = ChunkWaveMaterializer(job, plan, input) { warnings.add(it) }
        val language = runCatching { JobFiles.load<RecordingLanguage>(File(job, "language.json")) }
            .getOrNull()?.whisperLanguage ?: "ru"
        fun prepareOutput(output: File): File {
            if (request.resumeKey != null) JobCheckpoint.archiveRetryOutput(job, output)
            return output
        }

        StageTaskQueue.run(pending, workers, stopOnFailure = !giga, open = { index ->
            if (giga) {
                val files = if (profile.fast) GigaAmCandidateProvider.Models.ctc(
                    models.file("giga_fast_model"), models.file("giga_fast_tokens"))
                else GigaAmCandidateProvider.Models(models.file("giga_encoder"), models.file("giga_decoder"),
                    models.file("giga_joiner"), models.file("giga_tokens"))
                val provider = GigaAmCandidateProvider(files, threads = profile.gigaThreads)
                Recognizer(provider::generate, provider)
            } else {
                val session = runner.persistentExecutor(File(job, "workers/whisper-$index"))
                val metered = LocalNativeExecutor { executable, args, dir, stdout, stderr, timeout ->
                    session.run(executable, args, dir, stdout, stderr, timeout).also {
                        peak.updateAndGet { previous -> maxOf(previous, it.peakCombinedRssBytes) }
                    }
                }
                val provider = WhisperComparisonProvider(metered, models.file(profile.whisperModelId),
                    threads = profile.whisperThreads, audioContext = 512, language = language)
                Recognizer(provider::generate, session)
            }
        }, process = { recognizer, chunk ->
            val output = if (giga) {
                prepareOutput(File(job, "raw/gigaam/${chunk.id}.native.json"))
                prepareOutput(File(job, "raw/gigaam/${chunk.id}.json"))
            } else File(prepareOutput(File(job, "raw/whisper/${chunk.id}")), "whisper.json")
            waves.withChunk(chunk) { recognizer.generate(it, output) }
        }, collect = { chunk, attempt ->
            attempt.fold(onSuccess = { candidate ->
                references += CandidateRef(candidate.chunkId, candidate.source.wireName,
                    candidate.artifactFile.relativeTo(job).invariantSeparatorsPath, candidate.artifactSha256)
            }, onFailure = { failure ->
                warnings.add("${source.uppercase()}_FAILED:${chunk.id}:${failure.javaClass.simpleName}")
            })
            // This collector is the sole writer even when native completion order differs from input order.
            JobFiles.save(request.stage.resultFile(job), result())
            completed++
            onProgress(completed.toFloat() / plan.chunks.size.coerceAtLeast(1))
        })
        onProgress(1f)
        return result()
    }
}
