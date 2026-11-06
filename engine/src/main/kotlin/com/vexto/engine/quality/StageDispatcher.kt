package com.vexto.engine.quality

import android.content.Context
import com.vexto.engine.model.SpeakerTurn
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
    suspend fun run(context: Context, request: WorkerRequest): WorkerResult {
        val job = File(request.jobDir).canonicalFile
        val models = com.vexto.engine.models.QualityModels(QualityModelStore.root(context, QualityModelStore.manifest(context)),
            QualityModelStore.manifest(context))
        val input = JobFiles.inside(job, request.inputWav)
        val runner = NativeProcessRunner(context)
        var nativePeak = 0L
        val executor = LocalNativeExecutor { executable, args, dir, stdout, stderr, timeout ->
            runner.run(executable, args, dir, stdout, stderr, timeout).let {
                nativePeak = maxOf(nativePeak, it.peakCombinedRssBytes)
                LocalNativeResult(it.elapsedMs, it.peakCombinedRssBytes)
            }
        }
        fun chunks(): List<ComparisonChunk> {
            val plan = JobFiles.load<ChunkPlan>(File(job, "chunks.json")).also { it.validate() }
            return plan.chunks.map { ComparisonChunk(it.id, JobFiles.inside(job, it.wav), it.inputSha256, it.durationMs) }
        }
        fun reference(candidate: ImmutableAsrCandidate) = CandidateRef(candidate.chunkId, candidate.source.wireName,
            candidate.artifactFile.relativeTo(job).invariantSeparatorsPath, candidate.artifactSha256)
        fun checkpoint(result: WorkerResult) = JobFiles.save(request.stage.resultFile(job), result.copy(requestId = request.requestId))

        return when (request.stage) {
            WorkerStage.DENOISE -> {
                GtcrnDenoiserProvider(models.file("gtcrn")).use {
                    it.denoise(input, File(job, "denoised.wav"), File(job, "denoising.json"))
                }
                WorkerResult(request.stage)
            }
            WorkerStage.DIARIZE -> {
                val result = NativeDiarizationProvider.run(models.file("nemotron"), input,
                    File(job, "diarization/turns.rttm"), File(job, "diarization"), runner)
                val ids = result.turns.sortedBy { it.startMs }.map { it.speakerId }.distinct().withIndex().associate { it.value to it.index }
                val durationMs = com.vexto.engine.audio.PcmWave.inspect(input).sampleCount / 16
                val turns = result.turns.map { turn ->
                    require(turn.startMs < durationMs && turn.endMs <= durationMs + 200) { "Native RTTM outside recording" }
                    PipelineTurn(ids.getValue(turn.speakerId), turn.startMs, minOf(turn.endMs, durationMs))
                }
                JobFiles.save(File(job, "diarization/speaker-map.json"), ids)
                WorkerResult(request.stage, turns = turns, peakCombinedRssBytes = result.process.peakCombinedRssBytes)
            }
            WorkerStage.GIGAAM -> {
                val refs = mutableListOf<CandidateRef>()
                val warnings = mutableListOf<String>()
                GigaAmCandidateProvider(GigaAmCandidateProvider.Models(models.file("giga_encoder"),
                    models.file("giga_decoder"), models.file("giga_joiner"), models.file("giga_tokens"))).use { provider ->
                    for (chunk in chunks()) {
                        currentCoroutineContext().ensureActive()
                        try { refs += reference(provider.generate(chunk, File(job, "raw/gigaam/${chunk.id}.json"))) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { warnings += "GIGAAM_FAILED:${chunk.id}:${failure.javaClass.simpleName}" }
                        checkpoint(WorkerResult(request.stage, candidates = refs.toList(), warnings = warnings.toList()))
                    }
                }
                WorkerResult(request.stage, candidates = refs, warnings = warnings)
            }
            WorkerStage.WHISPER -> {
                val refs = mutableListOf<CandidateRef>()
                val warnings = mutableListOf<String>()
                val provider = WhisperComparisonProvider(executor, models.file("whisper"))
                for (chunk in chunks()) {
                    currentCoroutineContext().ensureActive()
                    try { refs += reference(provider.generate(chunk, File(job, "raw/whisper/${chunk.id}/whisper.json"))) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { warnings += "WHISPER_FAILED:${chunk.id}:${failure.javaClass.simpleName}" }
                    checkpoint(WorkerResult(request.stage, candidates = refs.toList(), warnings = warnings.toList(), peakCombinedRssBytes = nativePeak))
                }
                WorkerResult(request.stage, candidates = refs, warnings = warnings, peakCombinedRssBytes = nativePeak)
            }
            WorkerStage.SELECT -> {
                val refs = listOf(WorkerStage.GIGAAM, WorkerStage.WHISPER).flatMap { stage ->
                    runCatching { JobFiles.load<WorkerResult>(stage.resultFile(job)).candidates }.getOrDefault(emptyList())
                }
                val choices = mutableListOf<ChoiceRef>()
                val warnings = mutableListOf<String>()
                val selector = QwenAsrSelector(executor, models.file("qwen"))
                for (chunk in chunks()) {
                    currentCoroutineContext().ensureActive()
                    val originals = refs.filter { it.chunkId == chunk.id }.mapNotNull { ref ->
                        runCatching {
                            ImmutableAsrCandidate.load(JobFiles.inside(job, ref.file), ref.sha256).also {
                                it.verifyIdentity(chunk.id, chunk.inputSha256, AsrSource.entries.single { source -> source.wireName == ref.source })
                            }
                        }.getOrElse { warnings += "INVALID_ORIGINAL:${chunk.id}:${ref.source}"; null }
                    }
                    if (originals.isEmpty()) { warnings += "ASR_MISSING:${chunk.id}"; continue }
                    val pair = AsrCandidatePair(chunk.id, chunk.inputSha256, originals.firstOrNull { it.source == AsrSource.GIGAAM },
                        originals.firstOrNull { it.source == AsrSource.WHISPER })
                    val selected = selector.selectAll(listOf(pair), File(job, "selection")).single()
                    val ref = CandidateRef(chunk.id, selected.displayCandidate.source.wireName,
                        selected.selectedArtifact.relativeTo(job).invariantSeparatorsPath, selected.audit.selectedSha256)
                    choices += ChoiceRef(chunk.id, ref, selected.status.name, selected.audit.reason)
                    JobFiles.save(File(job, "selection/${chunk.id}/audit.json"), selected.audit)
                    checkpoint(WorkerResult(request.stage, choices = choices.toList(), warnings = warnings.toList(), peakCombinedRssBytes = nativePeak))
                }
                WorkerResult(request.stage, choices = choices, warnings = warnings, peakCombinedRssBytes = nativePeak)
            }
            WorkerStage.EMOTION -> {
                val turns = JobFiles.load<List<PipelineTurn>>(File(job, "turns.json"))
                EmotionProvider(models.file("emotion")).use { provider ->
                    provider.annotate(input, turns.map { SpeakerTurn(it.speaker, it.startMs, it.endMs) }, File(job, "emotion.json"))
                }
                WorkerResult(request.stage)
            }
        }
    }
}
