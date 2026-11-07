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
        var nativePeak = 0L
        val executor = LocalNativeExecutor { executable, args, dir, stdout, stderr, timeout ->
            runner.run(executable, args, dir, stdout, stderr, timeout).let {
                nativePeak = maxOf(nativePeak, it.peakCombinedRssBytes)
                LocalNativeResult(it.elapsedMs, it.peakCombinedRssBytes)
            }
        }
        fun chunkPlan(): ChunkPlan = JobFiles.load<ChunkPlan>(File(job, "chunks.json")).also { it.validate() }
        fun reference(candidate: ImmutableAsrCandidate) = CandidateRef(candidate.chunkId, candidate.source.wireName,
            candidate.artifactFile.relativeTo(job).invariantSeparatorsPath, candidate.artifactSha256)
        fun checkpoint(result: WorkerResult) = JobFiles.save(request.stage.resultFile(job), result.copy(requestId = request.requestId))

        return when (request.stage) {
            WorkerStage.DENOISE -> {
                GtcrnDenoiserProvider(models.file("gtcrn")).use {
                    it.denoise(input, File(job, "denoised.wav"), File(job, "denoising.json"), onProgress)
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
            WorkerStage.VOICEPRINT -> {
                val turns = JobFiles.load<List<PipelineTurn>>(File(job, "turns.json"))
                val voices = VoiceprintProvider.run(models.file("voiceprint_embedding"), input, turns)
                JobFiles.save(File(job, "voiceprints.json"), voices)
                WorkerResult(request.stage, voices = voices)
            }
            WorkerStage.GIGAAM -> {
                val refs = mutableListOf<CandidateRef>()
                val warnings = mutableListOf<String>()
                val plan = chunkPlan()
                val waves = ChunkWaveMaterializer(job, plan, input) { warnings += it }
                GigaAmCandidateProvider(GigaAmCandidateProvider.Models(models.file("giga_encoder"),
                    models.file("giga_decoder"), models.file("giga_joiner"), models.file("giga_tokens"))).use { provider ->
                    val tasks = plan.chunks
                    for ((index, chunk) in tasks.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        try {
                            val candidate = waves.withChunk(chunk) { current ->
                                provider.generate(current, File(job, "raw/gigaam/${chunk.id}.json"))
                            }
                            refs += reference(candidate)
                        }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { warnings += "GIGAAM_FAILED:${chunk.id}:${failure.javaClass.simpleName}" }
                        checkpoint(WorkerResult(request.stage, candidates = refs.toList(), warnings = warnings.toList()))
                        onProgress((index + 1f) / tasks.size)
                    }
                }
                WorkerResult(request.stage, candidates = refs, warnings = warnings)
            }
            WorkerStage.WHISPER -> {
                val refs = mutableListOf<CandidateRef>()
                val warnings = mutableListOf<String>()
                // 512 encoder frames cover 10.24s. Longer GigaAM chunks are windowed inside the provider.
                val provider = WhisperComparisonProvider(executor, models.file("whisper"), audioContext = 512)
                val plan = chunkPlan()
                val waves = ChunkWaveMaterializer(job, plan, input) { warnings += it }
                val tasks = plan.chunks

                for ((index, chunk) in tasks.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val candidate = waves.withChunk(chunk) { current ->
                            provider.generate(current, File(job, "raw/whisper/${chunk.id}/whisper.json"))
                        }
                        refs += reference(candidate)
                    }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { warnings += "WHISPER_FAILED:${chunk.id}:${failure.javaClass.simpleName}" }
                    checkpoint(WorkerResult(request.stage, candidates = refs.toList(), warnings = warnings.toList(), peakCombinedRssBytes = nativePeak))
                    onProgress((index + 1f) / tasks.size)
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
                val tasks = chunkPlan().chunks
                for ((index, chunk) in tasks.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val originals = refs.filter { it.chunkId == chunk.id }.mapNotNull { ref ->
                        runCatching {
                            ImmutableAsrCandidate.load(JobFiles.inside(job, ref.file), ref.sha256).also {
                                it.verifyIdentity(chunk.id, chunk.inputSha256, AsrSource.entries.single { source -> source.wireName == ref.source })
                            }
                        }.getOrElse { warnings += "INVALID_ORIGINAL:${chunk.id}:${ref.source}"; null }
                    }
                    if (originals.isEmpty()) { warnings += "ASR_MISSING:${chunk.id}"; onProgress((index + 1f) / tasks.size); continue }
                    val pair = AsrCandidatePair(chunk.id, chunk.inputSha256, originals.firstOrNull { it.source == AsrSource.GIGAAM },
                        originals.firstOrNull { it.source == AsrSource.WHISPER })
                    val selected = selector.selectAll(listOf(pair), File(job, "selection")).single()
                    val ref = CandidateRef(chunk.id, selected.displayCandidate.source.wireName,
                        selected.selectedArtifact.relativeTo(job).invariantSeparatorsPath, selected.audit.selectedSha256)
                    choices += ChoiceRef(chunk.id, ref, selected.status.name, selected.audit.reason)
                    JobFiles.save(File(job, "selection/${chunk.id}/audit.json"), selected.audit)
                    checkpoint(WorkerResult(request.stage, choices = choices.toList(), warnings = warnings.toList(), peakCombinedRssBytes = nativePeak))
                    onProgress((index + 1f) / tasks.size)
                }
                WorkerResult(request.stage, choices = choices, warnings = warnings, peakCombinedRssBytes = nativePeak)
            }
            WorkerStage.EMOTION -> {
                val targets = JobFiles.load<List<UtteranceEmotionTarget>>(File(job, "emotion-targets.json"))
                fun save(emotions: List<UtteranceEmotionEvidence>) {
                    JobFiles.save(File(job, "emotion.json"), UtteranceEmotionArtifact(emotions))
                    checkpoint(WorkerResult(request.stage, emotions = emotions))
                    if (targets.isNotEmpty()) onProgress(emotions.size.toFloat() / targets.size)
                }
                save(emptyList())
                EmotionProvider(models.file("emotion")).use { provider ->
                    val emotions = provider.annotateUtterances(input, targets, ::save)
                    save(emotions)
                    WorkerResult(request.stage, emotions = emotions)
                }
            }
        }
    }
}
