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
import kotlinx.coroutines.flow.asFlow
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Each stage owns persistent models; global admission bounds concurrent stages. */
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
        require(request.stage in setOf(WorkerStage.GIGAAM, WorkerStage.WHISPER, WorkerStage.GIGAAM_REFINE, WorkerStage.WHISPER_REFINE))
        val profile = ProcessingProfile.read(job)
        val plan = (if (request.streaming) JobFiles.load<StreamingChunks>(File(job, "streaming-chunks.json")).plan
            else JobFiles.load<ChunkPlan>(File(job, "chunks.json"))).also { it.validate() }
        val warnings = ConcurrentLinkedQueue<String>()
        val saved = if (request.resumeKey == null) null else
            JobFiles.load<WorkerResult>(request.stage.resultFile(job)).takeIf {
                it.requestId == request.requestId && it.stage == request.stage
            }
        val giga = request.stage in setOf(WorkerStage.GIGAAM, WorkerStage.GIGAAM_REFINE)
        val refine = request.stage in setOf(WorkerStage.GIGAAM_REFINE, WorkerStage.WHISPER_REFINE)
        val light = profile.fast && !refine
        val raw = if (refine) "raw/refined" else "raw"
        val workers = if (profile.fast) 1 else if (giga) profile.gigaWorkers else profile.whisperWorkers
        val source = if (giga) "gigaam" else "whisper"
        val chunks = java.util.concurrent.ConcurrentHashMap(plan.chunks.associateBy { it.id })
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
        val expected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also { it.addAll(request.chunkIds?.toSet() ?: chunks.keys) }
        require(expected.all { chunks.containsKey(it) } && request.chunkIds?.distinct()?.size == request.chunkIds?.size)
        val pending = plan.chunks.filter { it.id in expected && it.id !in known }
        var completed = known.size
        val peak = AtomicLong(saved?.peakCombinedRssBytes ?: 0L)

        fun result() = WorkerResult(request.stage, requestId = request.requestId,
            candidates = references.sortedBy { it.chunkId }, warnings = warnings.toList(),
            peakCombinedRssBytes = peak.get())

        if (completed > 0) onProgress(completed.toFloat() / expected.size.coerceAtLeast(1))
        if (pending.isEmpty() && !request.streaming) {
            onProgress(1f)
            return result()
        }
        val slavic = JobFiles.load<RecordingLanguage>(File(job, "language.json")).route == RecordingLanguage.SLAVIC
        val readers = if (refine) listOfNotNull(WorkerStage.GIGAAM_REFINE.takeIf { slavic }, WorkerStage.WHISPER_REFINE)
            else listOfNotNull(WorkerStage.GIGAAM.takeIf { slavic }, WorkerStage.WHISPER)
        val waves = ChunkWaveMaterializer(job, plan, input, readers) { warnings.add(it) }
        val cadence = CheckpointCadence()
        val language = runCatching { JobFiles.load<RecordingLanguage>(File(job, "language.json")) }
            .getOrNull()?.whisperLanguage ?: "ru"
        fun prepareOutput(output: File): File {
            if (request.resumeKey != null) JobCheckpoint.archiveRetryOutput(job, output)
            return output
        }

        val tasks = if (request.streaming) StreamingRecognition.tasks(job, plan, known) { update ->
            waves.include(update)
            chunks.putAll(update.chunks.associateBy { it.id })
            expected.addAll(update.chunks.map { it.id })
        } else pending.asFlow()
        StageTaskQueue.runStream(tasks, workers, stopOnFailure = !giga, open = { index ->
            if (giga) {
                val files = if (light) GigaAmCandidateProvider.Models.ctc(
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
                val provider = WhisperComparisonProvider(metered, models.file(if (light) "whisper_fast" else "whisper"),
                    threads = if (light) 2 else 4, audioContext = 512, language = language)
                Recognizer(provider::generate, session)
            }
        }, process = { recognizer, chunk ->
            val output = if (giga) {
                prepareOutput(File(job, "$raw/gigaam/${chunk.id}.native.json"))
                prepareOutput(File(job, "$raw/gigaam/${chunk.id}.json"))
            } else File(prepareOutput(File(job, "$raw/whisper/${chunk.id}")), "whisper.json")
            waves.withChunk(chunk) { recognizer.generate(it, output) }
        }, collect = { chunk, attempt ->
            attempt.fold(onSuccess = { candidate ->
                references += CandidateRef(candidate.chunkId, candidate.source.wireName,
                    candidate.artifactFile.relativeTo(job).invariantSeparatorsPath, candidate.artifactSha256)
            }, onFailure = { failure ->
                warnings.add("${source.uppercase()}_FAILED:${chunk.id}:${failure.javaClass.simpleName}")
            })
            // This collector is the sole writer even when native completion order differs from input order.
            completed++
            if (cadence.due(completed, expected.size)) {
                JobFiles.save(request.stage.resultFile(job), result())
                waves.releaseCompleted(references.mapNotNull { chunks[it.chunkId] })
            }
            onProgress(completed.toFloat() / expected.size.coerceAtLeast(1))
        })
        JobFiles.save(request.stage.resultFile(job), result())
        waves.releaseCompleted(references.mapNotNull { chunks[it.chunkId] })
        onProgress(1f)
        return result()
    }
}
