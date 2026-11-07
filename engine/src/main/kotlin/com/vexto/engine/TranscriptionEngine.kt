package com.vexto.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.vexto.engine.audio.AudioDecoder
import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.*
import com.vexto.engine.model.export.TranscriptExport
import com.vexto.engine.models.QualityModelStore
import com.vexto.engine.quality.*
import com.vexto.engine.review.*
import com.vexto.engine.pipeline.RecordingLanguage
import com.vexto.engine.pipeline.SpokenLanguageIdentifier
import com.vexto.engine.pipeline.UtteranceEmotionAnnotations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

/** File-backed profile. Each heavy model gets a fresh `:inference` process. Language id overlaps diarization in the app process. */
class TranscriptionEngine(context: Context) {
    private val context = context.applicationContext

    fun transcribe(audio: Uri, options: TranscriptionOptions = TranscriptionOptions()): Flow<TranscriptionEvent> =
        transcribe(audio, options, null)

    internal fun transcribeResumable(audio: Uri, options: TranscriptionOptions, jobId: String): Flow<TranscriptionEvent> =
        transcribe(audio, options, jobId)

    private fun transcribe(audio: Uri, options: TranscriptionOptions, jobId: String?): Flow<TranscriptionEvent> =
        channelFlow {
            transcriptionRequests.incrementAndGet()
            try {
                LocalConversationSummary.cancelTitle()
                lock.withLock { run(audio, options, jobId) }
            } finally {
                transcriptionRequests.decrementAndGet()
            }
        }.flowOn(Dispatchers.Default)

    private suspend fun ProducerScope<TranscriptionEvent>.run(uri: Uri, options: TranscriptionOptions, jobId: String?) {
        val started = SystemClock.elapsedRealtime()
        val times = linkedMapOf<Stage, Long>()
        val warnings = mutableListOf<String>()
        var peak = 0L
        suspend fun <T> stage(name: Stage, block: suspend ((Float) -> Unit) -> T): T {
            val t0 = SystemClock.elapsedRealtime()
            val progress: (Float) -> Unit = { trySend(TranscriptionEvent.Progress(name, it.coerceIn(0f, 1f))); Unit }
            progress(0f)
            val value = block(progress)
            progress(1f)
            times[name] = (times[name] ?: 0L) + SystemClock.elapsedRealtime() - t0
            return value
        }
        val manifest = QualityModelStore.manifest(context)
        val jobRoot = File(context.filesDir, "inference/jobs")
        val checkpoint = jobId?.let {
            require(uri.scheme == "file") { "Resumable transcription requires a saved recording" }
            JobCheckpoint.open(jobRoot, it, File(requireNotNull(uri.path)), manifest,
                options.speakerCount, options.processingMode)
        }
        val job = checkpoint?.directory ?: File(jobRoot, UUID.randomUUID().toString()).apply { check(mkdirs()) }
        checkpoint?.finished()?.let { send(TranscriptionEvent.Done(it.transcript, it.stats)); return }
        if (options.speakerCount != null) warnings += "Nemotron automatically determines speakers; requested fixed count is not applied"
        val original = File(job, "original.wav")
        val originalHash = checkpoint?.decodedHash() ?: stage(Stage.Decoding) { progress ->
            // Only an interrupted decode leaves these temporary files in this job directory.
            job.listFiles()?.filter { it.name.startsWith("decoded") && it.name.endsWith(".pcm") }
                ?.forEach { Files.deleteIfExists(it.toPath()) }
            val decoded = File(job, "decoded.wav.pending")
            try {
                AudioDecoder.decode(context, uri, job, progress).use { PcmWave.fromPcm(it.file, decoded, it.sampleCount) }
                Files.move(decoded.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                checkpoint?.checkpointDecoded(original).orEmpty()
            } finally { decoded.delete() }
        }
        val audioMs = PcmWave.inspect(original).sampleCount * 1000 / 16000
        // Resolve AUTO from decoded samples, never from an untrusted container duration estimate.
        val profile = ProcessingProfile.resolve(options.processingMode, audioMs)
        val profileFile = File(job, "processing-profile.json")
        if (checkpoint != null && profileFile.isFile) require(ProcessingProfile.read(job) == profile) {
            "Processing profile changed since this transcription was started"
        }
        JobFiles.save(profileFile, profile)
        val profileKey = sha256(JobFiles.json.encodeToString(profile).toByteArray(Charsets.UTF_8))
        val models = stage(Stage.Preparing) { QualityModelStore.prepare(context, profile.fast, it) }
        JobFiles.save(File(job, "job.json"), mapOf("source_uri" to uri.toString(), "profile" to models.manifest.version,
            "requested_mode" to profile.requestedMode.name, "effective_mode" to profile.mode.name,
            "memory_budget_bytes" to AppRss.BudgetBytes.toString(), "reserve_bytes" to AppRss.ReserveBytes.toString()))
        JobFiles.save(File(job, "models.json"), models.manifest)
        val client = StageClient(context)
        val results = mutableListOf<WorkerResult>()
        suspend fun worker(
            kind: WorkerStage,
            name: Stage,
            input: String,
            inputHash: String = originalHash,
            dependencies: List<String> = emptyList(),
            plan: ChunkPlan? = null,
        ): WorkerResult {
            val (request, saved) = checkpoint?.worker(kind, input, inputHash, listOf(profileKey) + dependencies, plan)
                ?: (WorkerRequest(kind, job.absolutePath, input) to null)
            val result = if (saved?.completed == true) {
                times[name] = saved.elapsedMs
                saved
            } else stage(name) { progress ->
                val first = client.run(request, saved, progress)
                val attemptedNpu = kind == WorkerStage.EMOTION && !first.completed && request.allowNpu &&
                    runCatching { JobFiles.load<String>(emotionNpuAttemptFile(job)) }.getOrNull() == request.requestId
                if (!attemptedNpu) first else {
                    // The previous Binder is already dead. Reuse verified utterances and retry on CPU once.
                    val retry = request.copy(requestId = UUID.randomUUID().toString(), allowNpu = false)
                    val partial = checkpoint?.worker(kind, input, inputHash, dependencies, plan)?.second
                        ?.copy(requestId = retry.requestId)
                    client.run(retry, partial, progress)
                }
            }
            results += result
            peak = maxOf(peak, result.peakCombinedRssBytes)
            warnings += result.warnings
            if (!result.completed) warnings += "${kind.name}: ${result.error ?: "incomplete"}"
            return result
        }
        val denoise = if (profile.denoise) worker(WorkerStage.DENOISE, Stage.Denoising, "original.wav") else null
        val view = if (denoise != null) {
            val cleaned = File(job, "denoised.wav")
            if (denoise.completed && cleaned.isFile &&
                PcmWave.inspect(cleaned).sampleCount == PcmWave.inspect(original).sampleCount) "denoised.wav" else {
                warnings += "ASR uses original audio because denoising did not produce a valid aligned-length view"
                "original.wav"
            }
        } else "original.wav"
        val viewHash = if (view == "denoised.wav") denoise?.outputSha256.orEmpty() else originalHash
        // Language id stays in this process: the inference worker accepts one stage at a time.
        val languageFile = File(job, "language.json")
        val (diarization, languageResult) = coroutineScope {
            val identified = async {
                val t0 = SystemClock.elapsedRealtime()
                val savedLanguage = checkpoint?.language(originalHash)
                val detected = savedLanguage ?: try {
                    SpokenLanguageIdentifier.identifyRecording(
                        models.file("lid_encoder"), models.file("lid_decoder"), original)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    RecordingLanguage.undetected()
                }
                if (checkpoint != null && savedLanguage == null) checkpoint.saveLanguage(originalHash, detected)
                detected to (SystemClock.elapsedRealtime() - t0)
            }
            worker(WorkerStage.DIARIZE, Stage.Diarization, "original.wav") to identified.await()
        }
        val (recordingLanguage, languageMs) = languageResult
        times[Stage.Language] = languageMs
        warnings += if (recordingLanguage.detected) "LANGUAGE:${recordingLanguage.code}" else "LANGUAGE_ID_UNAVAILABLE"
        JobFiles.save(languageFile, recordingLanguage)
        val slavic = recordingLanguage.route == RecordingLanguage.SLAVIC
        val turns = diarization.turns
        val turnsFile = File(job, "turns.json")
        JobFiles.save(turnsFile, turns)
        // Signature extraction uses original audio and its own model lifetime, never clustering again.
        val voices = if (profile.speakerAnnotations) worker(WorkerStage.VOICEPRINT, Stage.Voiceprints, "original.wav",
            dependencies = if (checkpoint != null) listOf(JobFiles.sha256(turnsFile)) else emptyList()).voices
            else emptyMap()
        fun attachVoices(transcript: Transcript): Transcript = transcript.copy(speakers = transcript.speakers.map { speaker ->
            val acousticId = speaker.id.takeIf { it.startsWith("speaker_") }
                ?.removePrefix("speaker_")?.toIntOrNull()?.minus(1)
            speaker.copy(voice = acousticId?.let(voices::get))
        })
        // The speech mask is a dependency of the plan, including whether diarization completed.
        val planDependency = sha256((JobFiles.sha256(turnsFile) + ":" + diarization.completed + ":" +
            (diarization.error == null)).toByteArray(Charsets.UTF_8))
        val planBinding = File(job, "chunk-diarization.sha256")
        val cachedPlan = checkpoint?.chunks(view, viewHash)?.takeIf {
            runCatching { planBinding.readText() == planDependency }.getOrDefault(false)
        }
        val plan = cachedPlan ?: ChunkPlan.create(File(job, view), job,
            turns.takeIf { diarization.completed && diarization.error == null }).also {
            JobFiles.write(planBinding, planDependency.toByteArray(Charsets.US_ASCII))
        }
        val planHash = if (checkpoint != null) JobFiles.sha256(File(job, "chunks.json")) else ""
        val assembler = CandidateTranscriptAssembler()
        if (!slavic) warnings += "ASR_ROUTE:whisper_only:${recordingLanguage.code}"
        val giga = if (slavic) worker(WorkerStage.GIGAAM, Stage.Recognition, view, viewHash, listOf(planHash), plan) else null
        val gigaCandidates: Map<String, ImmutableAsrCandidate> = if (giga == null) emptyMap()
            else loadCandidates(job, plan, giga.candidates, warnings)
        if (slavic && gigaCandidates.size == plan.chunks.size && gigaCandidates.isNotEmpty()) {
            val core = attachVoices(AcousticTurnTranscript.place(
                assembler.assemble(plan, gigaCandidates, turns).transcript, turns))
            // Originals are already durable before this callback. Refinement never destroys this checkpoint.
            JobFiles.write(File(job, "core.txt"), plan.chunks.joinToString("\n") { gigaCandidates.getValue(it.id).text }.toByteArray())
            JobFiles.write(File(job, "core.json"), TranscriptExport.json(core).toByteArray(Charsets.UTF_8))
            send(TranscriptionEvent.CoreReady(core, job.absolutePath))
        }
        val whisper = worker(WorkerStage.WHISPER, Stage.Comparison, view, viewHash,
            listOf(planHash, recordingLanguage.whisperLanguage), plan)
        val reviewedChoices = if (slavic) worker(WorkerStage.SELECT, Stage.Selection, view, viewHash,
            listOf(planHash, JobFiles.json.encodeToString(giga?.candidates.orEmpty()),
                JobFiles.json.encodeToString(whisper.candidates)), plan).choices else emptyList()
        val choices = reviewedChoices.associateBy { it.chunkId }.toMutableMap()
        // Failure is explicitly unresolved, never interpreted as a quality win for either recognizer.
        val all = loadCandidates(job, plan, giga?.candidates.orEmpty() + whisper.candidates, warnings, allowMultiple = true)
        if (!slavic) {
            val whisperCandidates = all.values.filter { it.source == AsrSource.WHISPER }.associateBy { it.chunkId }
            if (whisperCandidates.size == plan.chunks.size && whisperCandidates.isNotEmpty()) {
                val core = attachVoices(AcousticTurnTranscript.place(
                    assembler.assemble(plan, whisperCandidates, turns).transcript, turns))
                JobFiles.write(File(job, "core.txt"), plan.chunks.joinToString("\n") { whisperCandidates.getValue(it.id).text }.toByteArray())
                JobFiles.write(File(job, "core.json"), TranscriptExport.json(core).toByteArray(Charsets.UTF_8))
                send(TranscriptionEvent.CoreReady(core, job.absolutePath))
            }
        }
        val selectionTool = AsrSelectionTool()
        val fallbackReason = if (slavic) "selector_stage_failed" else "language_route_whisper_only"
        plan.chunks.filter { it.id !in choices }.forEach { chunk ->
            val candidates = all.values.filter { it.chunkId == chunk.id }
            if (candidates.isEmpty()) { warnings += "ASR_MISSING:${chunk.id}"; return@forEach }
            val pair = AsrCandidatePair(chunk.id, chunk.inputSha256,
                candidates.firstOrNull { it.source == AsrSource.GIGAAM }, candidates.firstOrNull { it.source == AsrSource.WHISPER })
            val output = File(job, "selected/fallback-${chunk.id}.json")
            if (checkpoint != null) JobCheckpoint.archiveRetryOutput(job, output)
            val selection = selectionTool.fallback(pair, output, fallbackReason)
            if (!slavic) JobFiles.save(File(job, "selection/${chunk.id}/audit.json"), selection.audit)
            choices[chunk.id] = ChoiceRef(chunk.id, CandidateRef(chunk.id, selection.displayCandidate.source.wireName,
                output.relativeTo(job).invariantSeparatorsPath, selection.displayCandidate.artifactSha256),
                selection.status.name, selection.audit.reason)
        }
        val selected = loadCandidates(job, plan, choices.values.map { it.candidate }, warnings)
        // Qwen may pick a Whisper chunk that drops words. The scored text stays the raw GigaAM candidate.
        val display = if (gigaCandidates.size == plan.chunks.size && gigaCandidates.isNotEmpty()) {
            warnings += "EXPORT_TEXT:gigaam_raw"
            gigaCandidates
        } else selected
        val assembled = stage(Stage.Assembly) {
            val base = assembler.assemble(plan, display, turns)
            base.copy(transcript = AcousticTurnTranscript.place(base.transcript, turns))
        }
        if (assembled.estimatedTimingChunks.isNotEmpty()) warnings += "Estimated display word timing: ${assembled.estimatedTimingChunks.joinToString()}"
        val unresolved = choices.values.count { it.status == AsrSelectionStatus.UNRESOLVED.name }
        if (unresolved > 0) warnings += "LLM_UNRESOLVED:$unresolved (display fallback only; both originals retained)"
        JobFiles.save(File(job, "selection.json"), choices.values.sortedBy { it.chunkId })
        JobFiles.write(File(job, "selected.txt"), plan.chunks.mapNotNull { selected[it.id]?.text }.joinToString("\n").toByteArray())
        var transcript = attachVoices(assembled.transcript)
        if (profile.speakerAnnotations) {
            val emotionTargets = UtteranceEmotionAnnotations.targets(transcript)
            val emotionTargetsFile = File(job, "emotion-targets.json")
            JobFiles.save(emotionTargetsFile, emotionTargets)
            // The worker receives exact line identity and returns a score only for that line's original audio.
            val emotions = worker(WorkerStage.EMOTION, Stage.Emotion, "original.wav",
                dependencies = if (checkpoint != null) listOf(JobFiles.sha256(emotionTargetsFile)) else emptyList())
            transcript = runCatching { UtteranceEmotionAnnotations.attach(transcript, emotions.emotions) }
                .getOrElse { warnings += "EMOTION_MAPPING_FAILED:${it.javaClass.simpleName}"; transcript }
            val unscored = transcript.utterances.count { it.endMs > it.startMs && it.emotionProbabilities == null }
            if (unscored > 0) warnings += "EMOTION_FAILED:unscored_utterances=$unscored"
        }
        val coverage = PipelineCoverage(plan.chunks.size,
            all.values.count { it.source == AsrSource.GIGAAM }, all.values.count { it.source == AsrSource.WHISPER },
            selected.size, choices.values.count { it.status == AsrSelectionStatus.SELECTED_BY_LLM.name }, unresolved)
        val withinBudget = peak + AppRss.ReserveBytes <= AppRss.BudgetBytes
        val expectedStages = profile.expectedStages(slavic)
        val allStagesSucceeded = results.map { it.stage }.toSet() == expectedStages &&
            results.all { it.completed && it.error == null }
        val fullProfile = coverage.fullProfileCompleted(allStagesSucceeded, warnings, withinBudget)
        val export = File(job, "transcript.json")
        val metadata = File(job, "transcript.metadata.json")
        JobFiles.write(export, TranscriptExport.submissionJson(transcript).toByteArray(Charsets.UTF_8))
        JobFiles.write(metadata, TranscriptExport.jsonWithMetadata(transcript).toByteArray(Charsets.UTF_8))
        val whisperName = if (profile.fast) "Whisper-small-q5_1" else "Whisper-large-v3-q5_0"
        val gigaName = if (profile.fast) "GigaAM-v3-e2e-ctc-int8" else "GigaAM-v3-e2e-rnnt-int8"
        val asrModel = if (slavic) "$gigaName+$whisperName; selection-only Qwen3-1.7B-Q4_K_M"
            else "$whisperName; language=${recordingLanguage.code}; gigaam_skipped"
        val stats = ProcessingStats(audioMs, SystemClock.elapsedRealtime() - started, times,
            peak / (1024 * 1024), asrModel, 4,
            artifactDirectory = job.absolutePath, warnings = warnings.distinct(), complete = coverage.complete,
            fullProfileCompleted = fullProfile, selectionResolved = coverage.selectionResolved)
        JobFiles.save(File(job, "completion.json"), PipelineReport(audioMs = audioMs, processingMs = stats.processingMs,
            modelProfile = models.manifest.version, coverage = coverage, complete = coverage.complete,
            fullProfileCompleted = fullProfile, selectionResolved = coverage.selectionResolved,
            peakRssBytes = peak, reserveBytes = AppRss.ReserveBytes, budgetBytes = AppRss.BudgetBytes,
            stageMs = times.mapKeys { it.key.name }, stages = results.toList(),
            transcriptSha256 = JobFiles.sha256(export), metadataSha256 = JobFiles.sha256(metadata), warnings = stats.warnings,
            modelVerificationReused = models.verificationReused, processingProfile = profile,
            skippedSilenceMs = plan.skippedSamples / 16))
        checkpoint?.saveFinished(transcript, stats)
        send(TranscriptionEvent.Done(transcript, stats))
    }

    private fun loadCandidates(job: File, plan: ChunkPlan, refs: List<CandidateRef>, warnings: MutableList<String>,
                               allowMultiple: Boolean = false): Map<String, ImmutableAsrCandidate> {
        val chunks = plan.chunks.associateBy { it.id }
        return refs.mapNotNull { ref ->
            runCatching {
                val chunk = chunks.getValue(ref.chunkId)
                val candidate = ImmutableAsrCandidate.load(JobFiles.inside(job, ref.file), ref.sha256)
                val source = AsrSource.entries.single { it.wireName == ref.source }
                candidate.verifyIdentity(chunk.id, chunk.inputSha256, source)
                (if (allowMultiple) "${chunk.id}:${ref.source}" else chunk.id) to candidate
            }.getOrElse { warnings += "INVALID_CANDIDATE:${ref.chunkId}:${ref.source}"; null }
        }.toMap()
    }

    internal companion object {
        val lock = Mutex()
        private val transcriptionRequests = AtomicInteger()
        val isTranscriptionPending: Boolean get() = transcriptionRequests.get() > 0
    }
}
