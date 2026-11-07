package com.vexto.engine.quality

import android.content.Context
import com.vexto.engine.models.QualityModelStore
import com.vexto.engine.pipeline.*
import com.vexto.engine.review.*
import com.vexto.engine.runtime.NativeDiarizationProvider
import com.vexto.engine.runtime.NativeProcessRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Model adapters know nothing about the UI, backend or recording storage. */
internal object StageDispatcher {
    suspend fun run(context: Context, request: WorkerRequest, onProgress: (Float) -> Unit = {}): WorkerResult {
        val job = File(request.jobDir).canonicalFile
        val models = com.vexto.engine.models.QualityModels(QualityModelStore.root(context, QualityModelStore.manifest(context)),
            QualityModelStore.manifest(context))
        val input = JobFiles.inside(job, request.inputWav)
        val runner = NativeProcessRunner(context)
        val saved = if (request.resumeKey == null) null else
            JobFiles.load<WorkerResult>(request.stage.resultFile(job)).takeIf {
                it.requestId == request.requestId && it.stage == request.stage
            }
        var nativePeak = saved?.peakCombinedRssBytes ?: 0L
        fun chunkPlan(): ChunkPlan = JobFiles.load<ChunkPlan>(File(job, "chunks.json")).also { it.validate() }
        fun checkpoint(result: WorkerResult) = JobFiles.save(request.stage.resultFile(job), result.copy(requestId = request.requestId))

        fun prepareOutput(output: File): File {
            if (request.resumeKey != null) JobCheckpoint.archiveRetryOutput(job, output)
            return output
        }

        return when (request.stage) {
            WorkerStage.DENOISE -> {
                val denoised = prepareOutput(File(job, "denoised.wav"))
                val audit = prepareOutput(File(job, "denoising.json"))
                GtcrnDenoiserProvider(models.file("gtcrn")).use { it.denoise(input, denoised, audit, onProgress) }
                WorkerResult(request.stage, outputSha256 = if (request.resumeKey != null) JobFiles.sha256(denoised) else null)
            }
            WorkerStage.DIARIZE -> {
                val work = prepareOutput(File(job, "diarization"))
                val result = NativeDiarizationProvider.run(models.file("nemotron"), input,
                    File(work, "turns.rttm"), work, runner)
                val ids = result.turns.sortedBy { it.startMs }.map { it.speakerId }.distinct().withIndex().associate { it.value to it.index }
                val durationMs = com.vexto.engine.audio.PcmWave.inspect(input).sampleCount / 16
                val turns = result.turns.map { turn ->
                    require(turn.startMs < durationMs && turn.endMs <= durationMs + 200) { "Native RTTM outside recording" }
                    PipelineTurn(ids.getValue(turn.speakerId), turn.startMs, minOf(turn.endMs, durationMs))
                }
                JobFiles.save(File(job, "diarization/speaker-map.json"), ids)
                WorkerResult(request.stage, turns = turns, peakCombinedRssBytes = result.process.peakCombinedRssBytes)
            }
            WorkerStage.VOICEPRINT -> {
                val turns = JobFiles.load<List<PipelineTurn>>(File(job, "turns.json"))
                val voices = VoiceprintProvider.run(models.file("voiceprint_embedding"), input, turns)
                JobFiles.save(File(job, "voiceprints.json"), voices)
                WorkerResult(request.stage, voices = voices)
            }
            WorkerStage.GIGAAM, WorkerStage.WHISPER ->
                QueuedAsrStages.run(request, job, input, models, runner, onProgress)
            WorkerStage.SELECT -> {
                val refs = listOf(WorkerStage.GIGAAM, WorkerStage.WHISPER).flatMap { stage ->
                    runCatching { JobFiles.load<WorkerResult>(stage.resultFile(job)).candidates }.getOrDefault(emptyList())
                }
                val choices = saved?.choices.orEmpty().toMutableList()
                val known = choices.mapTo(mutableSetOf()) { it.chunkId }
                val warnings = mutableListOf<String>()
                val tasks = chunkPlan().chunks
                var processed = known.size
                if (processed > 0) onProgress(processed.toFloat() / tasks.size.coerceAtLeast(1))
                if (processed == tasks.size) {
                    onProgress(1f)
                    return WorkerResult(request.stage, choices = choices, peakCombinedRssBytes = nativePeak)
                }
                runner.persistentExecutor(File(job, "workers/qwen")).use { session ->
                    val metered = LocalNativeExecutor { executable, args, dir, stdout, stderr, timeout ->
                        session.run(executable, args, dir, stdout, stderr, timeout).also {
                            nativePeak = maxOf(nativePeak, it.peakCombinedRssBytes)
                        }
                    }
                    val selector = QwenAsrSelector(metered, models.file("qwen"))
                    for (chunk in tasks) {
                        currentCoroutineContext().ensureActive()
                        if (chunk.id in known) continue
                        val originals = refs.filter { it.chunkId == chunk.id }.mapNotNull { ref ->
                            runCatching {
                                ImmutableAsrCandidate.load(JobFiles.inside(job, ref.file), ref.sha256).also {
                                    it.verifyIdentity(chunk.id, chunk.inputSha256, AsrSource.entries.single { source -> source.wireName == ref.source })
                                }
                            }.getOrElse { warnings += "INVALID_ORIGINAL:${chunk.id}:${ref.source}"; null }
                        }
                        if (originals.isEmpty()) { warnings += "ASR_MISSING:${chunk.id}"; onProgress((++processed).toFloat() / tasks.size); continue }
                        val pair = AsrCandidatePair(chunk.id, chunk.inputSha256, originals.firstOrNull { it.source == AsrSource.GIGAAM },
                            originals.firstOrNull { it.source == AsrSource.WHISPER })
                        prepareOutput(File(job, "selection/${chunk.id}"))
                        val selected = selector.selectAll(listOf(pair), File(job, "selection")).single()
                        val ref = CandidateRef(chunk.id, selected.displayCandidate.source.wireName,
                            selected.selectedArtifact.relativeTo(job).invariantSeparatorsPath, selected.audit.selectedSha256)
                        choices += ChoiceRef(chunk.id, ref, selected.status.name, selected.audit.reason)
                        JobFiles.save(File(job, "selection/${chunk.id}/audit.json"), selected.audit)
                        checkpoint(WorkerResult(request.stage, choices = choices.toList(), warnings = warnings.toList(), peakCombinedRssBytes = nativePeak))
                        onProgress((++processed).toFloat() / tasks.size)
                    }
                }
                WorkerResult(request.stage, choices = choices, warnings = warnings, peakCombinedRssBytes = nativePeak)
            }
            WorkerStage.EMOTION -> {
                val targets = JobFiles.load<List<UtteranceEmotionTarget>>(File(job, "emotion-targets.json"))
                val evidence = saved?.emotions.orEmpty()
                val known = evidence.mapTo(mutableSetOf()) { it.target.index }
                val pending = targets.filter { it.index !in known }
                val backends = saved?.backends.orEmpty().toMutableSet()
                val accelerator = if (request.allowNpu && pending.isNotEmpty()) EmotionNative.npuDriver() else null
                if (accelerator != null) JobFiles.save(emotionNpuAttemptFile(job), request.requestId)

                fun save(emotions: List<UtteranceEmotionEvidence>): WorkerResult {
                    JobFiles.save(File(job, "emotion.json"), UtteranceEmotionArtifact(emotions))
                    val result = WorkerResult(request.stage, emotions = emotions, backends = backends.toList())
                    checkpoint(result)
                    if (targets.isNotEmpty()) onProgress(emotions.size.toFloat() / targets.size)
                    return result
                }
                save(evidence)
                EmotionProvider(models.file("emotion"), allowNpu = accelerator != null).use { provider ->
                    fun recordBackend() {
                        provider.backend?.let { backend ->
                            backends += when {
                                !request.allowNpu -> "cpu:after_worker_retry"
                                accelerator == null -> "cpu:no_compatible_npu"
                                else -> backend
                            }
                        }
                    }
                    val added = provider.annotateUtterances(input, pending) {
                        recordBackend()
                        save(evidence + it)
                    }
                    recordBackend()
                    save((evidence + added).sortedBy { it.target.index })
                }
            }
        }
    }
}
