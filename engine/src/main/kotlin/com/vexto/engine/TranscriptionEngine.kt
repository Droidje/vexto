package com.vexto.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.vexto.engine.audio.AudioDecoder
import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.*
import com.vexto.engine.models.QualityModelStore
import com.vexto.engine.quality.*
import com.vexto.engine.review.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Sequential, file-backed full profile. A fresh worker process owns each model lifetime. */
class TranscriptionEngine(context: Context) {
    private val context = context.applicationContext

    fun transcribe(audio: Uri, options: TranscriptionOptions = TranscriptionOptions()): Flow<TranscriptionEvent> =
        channelFlow { lock.withLock { run(audio, options) } }.flowOn(Dispatchers.Default)

    private suspend fun ProducerScope<TranscriptionEvent>.run(uri: Uri, options: TranscriptionOptions) {
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
            times[name] = SystemClock.elapsedRealtime() - t0
            return value
        }
        val models = stage(Stage.Preparing) { QualityModelStore.prepare(context, it) }
        val job = File(context.filesDir, "inference/jobs/${UUID.randomUUID()}").apply { check(mkdirs()) }
        JobFiles.save(File(job, "job.json"), mapOf("source_uri" to uri.toString(), "profile" to models.manifest.version,
            "memory_budget_bytes" to AppRss.BudgetBytes.toString(), "reserve_bytes" to AppRss.ReserveBytes.toString()))
        JobFiles.save(File(job, "models.json"), models.manifest)
        if (options.speakerCount != null) warnings += "Nemotron automatically determines speakers; requested fixed count is not applied"
        val original = File(job, "original.wav")
        stage(Stage.Decoding) { progress ->
            AudioDecoder.decode(context, uri, job, progress).use { PcmWave.fromPcm(it.file, original, it.sampleCount) }
        }
        val audioMs = PcmWave.inspect(original).sampleCount * 1000 / 16000
        val client = StageClient(context)
        val results = mutableListOf<WorkerResult>()
        suspend fun worker(kind: WorkerStage, name: Stage, input: String): WorkerResult = stage(name) {
            client.run(WorkerRequest(kind, job.absolutePath, input)).also { result ->
                results += result
                peak = maxOf(peak, result.peakCombinedRssBytes)
                warnings += result.warnings
                if (!result.completed) warnings += "${kind.name}: ${result.error ?: "incomplete"}"
            }
        }
        val denoise = worker(WorkerStage.DENOISE, Stage.Denoising, "original.wav")
        val cleaned = File(job, "denoised.wav")
        val view = if (denoise.completed && cleaned.isFile &&
            PcmWave.inspect(cleaned).sampleCount == PcmWave.inspect(original).sampleCount) "denoised.wav" else {
            warnings += "ASR uses original audio because denoising did not produce a valid aligned-length view"
            "original.wav"
        }
        val diarization = worker(WorkerStage.DIARIZE, Stage.Diarization, "original.wav")
        val turns = diarization.turns
        JobFiles.save(File(job, "turns.json"), turns)
        val plan = ChunkPlan.create(File(job, view), job)
        val assembler = CandidateTranscriptAssembler()
        val giga = worker(WorkerStage.GIGAAM, Stage.Recognition, view)
        val gigaCandidates = loadCandidates(job, plan, giga.candidates, warnings)
        if (gigaCandidates.size == plan.chunks.size && gigaCandidates.isNotEmpty()) {
            val core = assembler.assemble(plan, gigaCandidates, turns).transcript
            // Originals are already durable before this callback. Refinement never destroys this checkpoint.
            JobFiles.write(File(job, "core.txt"), plan.chunks.joinToString("\n") { gigaCandidates.getValue(it.id).text }.toByteArray())
            send(TranscriptionEvent.CoreReady(core, job.absolutePath))
        }
        val whisper = worker(WorkerStage.WHISPER, Stage.Comparison, view)
        val reviewed = worker(WorkerStage.SELECT, Stage.Selection, view)
        val choices = reviewed.choices.associateBy { it.chunkId }.toMutableMap()
        // Failure is explicitly unresolved, never interpreted as a quality win for either recognizer.
        val all = loadCandidates(job, plan, giga.candidates + whisper.candidates, warnings, allowMultiple = true)
        val selectionTool = AsrSelectionTool()
        plan.chunks.filter { it.id !in choices }.forEach { chunk ->
            val candidates = all.values.filter { it.chunkId == chunk.id }
            if (candidates.isEmpty()) { warnings += "ASR_MISSING:${chunk.id}"; return@forEach }
            val pair = AsrCandidatePair(chunk.id, chunk.inputSha256,
                candidates.firstOrNull { it.source == AsrSource.GIGAAM }, candidates.firstOrNull { it.source == AsrSource.WHISPER })
            val output = File(job, "selected/fallback-${chunk.id}.json")
            val selection = selectionTool.fallback(pair, output, "selector_stage_failed")
            choices[chunk.id] = ChoiceRef(chunk.id, CandidateRef(chunk.id, selection.displayCandidate.source.wireName,
                output.relativeTo(job).invariantSeparatorsPath, selection.displayCandidate.artifactSha256),
                selection.status.name, selection.audit.reason)
        }
        val selected = loadCandidates(job, plan, choices.values.map { it.candidate }, warnings)
        val assembled = stage(Stage.Assembly) { assembler.assemble(plan, selected, turns) }
        if (assembled.estimatedTimingChunks.isNotEmpty()) warnings += "Estimated display word timing: ${assembled.estimatedTimingChunks.joinToString()}"
        val unresolved = choices.values.count { it.status == AsrSelectionStatus.UNRESOLVED.name }
        if (unresolved > 0) warnings += "LLM_UNRESOLVED:$unresolved (display fallback only; both originals retained)"
        JobFiles.save(File(job, "selection.json"), choices.values.sortedBy { it.chunkId })
        JobFiles.write(File(job, "selected.txt"), plan.chunks.mapNotNull { selected[it.id]?.text }.joinToString("\n").toByteArray())
        worker(WorkerStage.EMOTION, Stage.Emotion, "original.wav")
        val stats = ProcessingStats(audioMs, SystemClock.elapsedRealtime() - started, times,
            peak / (1024 * 1024), "GigaAM-v3-e2e+Whisper-large-v3-q5_0; selection-only Qwen3-1.7B-Q4_K_M", 4,
            artifactDirectory = job.absolutePath, warnings = warnings.distinct(), complete = selected.size == plan.chunks.size,
            fullProfileCompleted = results.all { it.completed && it.error == null } && warnings.none { it.contains("_FAILED:") || it.startsWith("ASR_MISSING:") },
            selectionResolved = unresolved == 0)
        JobFiles.save(File(job, "completion.json"), mapOf("complete" to stats.complete.toString(),
            "warnings" to stats.warnings.joinToString("\n"), "peak_rss_bytes" to peak.toString()))
        send(TranscriptionEvent.Done(assembled.transcript, stats))
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

    private companion object { val lock = Mutex() }
}
