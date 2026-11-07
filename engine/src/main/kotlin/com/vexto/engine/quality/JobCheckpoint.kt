package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.ProcessingStats
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.export.TranscriptExport
import com.vexto.engine.models.QualityManifest
import com.vexto.engine.pipeline.RecordingLanguage
import com.vexto.engine.pipeline.UtteranceEmotionTarget
import com.vexto.engine.review.AsrSelectionStatus
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.ImmutableAsrCandidate
import com.vexto.engine.review.SelectionAudit
import com.vexto.engine.review.sha256
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable

/** Keeps a resumed run bound to the same audio and exact model pack. */
internal class JobCheckpoint private constructor(val directory: File) {
    @Serializable
    private data class Identity(
        val schema: Int = 1,
        val audioSha256: String,
        val models: QualityManifest,
        val speakerCount: Int?,
    )

    fun decodedHash(): String? = runCatching {
        val wav = File(directory, "original.wav")
        val expected = File(directory, "decoded.sha256").readText()
        require(PcmWave.inspect(wav).sampleCount > 0 && JobFiles.sha256(wav) == expected)
        expected
    }.getOrNull()

    fun checkpointDecoded(wav: File): String =
        JobFiles.sha256(wav).also { JobFiles.write(File(directory, "decoded.sha256"), it.toByteArray(Charsets.US_ASCII)) }

    @Serializable
    data class Finished(val transcript: Transcript, val stats: ProcessingStats)

    @Serializable
    private data class LanguageCheckpoint(val inputSha256: String, val language: RecordingLanguage)

    fun language(inputSha256: String): RecordingLanguage? =
        runCatching { JobFiles.load<LanguageCheckpoint>(File(directory, "language.checkpoint.json")) }
            .getOrNull()?.takeIf { it.inputSha256 == inputSha256 }?.language

    fun saveLanguage(inputSha256: String, language: RecordingLanguage) =
        JobFiles.save(File(directory, "language.checkpoint.json"), LanguageCheckpoint(inputSha256, language))

    fun chunks(inputWav: String, inputSha256: String): ChunkPlan? = runCatching {
        val storage = JobFiles.load<ChunkStorage>(File(directory, "chunk-storage.json"))
        require(storage == ChunkStorage(inputWav = inputWav, inputSha256 = inputSha256))
        JobFiles.load<ChunkPlan>(File(directory, "chunks.json")).also {
            it.validate()
            require(it.sampleCount == PcmWave.inspect(File(directory, inputWav)).sampleCount)
        }
    }.getOrNull()

    fun finished(): Finished? = runCatching {
        val result = JobFiles.load<Finished>(File(directory, "finished.checkpoint.json"))
        val report = JobFiles.load<PipelineReport>(File(directory, "completion.json"))
        require(result.stats.artifactDirectory == directory.absolutePath)
        require(JobFiles.sha256(File(directory, "transcript.json")) == report.transcriptSha256 &&
            JobFiles.sha256(File(directory, "transcript.metadata.json")) == report.metadataSha256)
        require(sha256(TranscriptExport.jsonWithMetadata(result.transcript).toByteArray(Charsets.UTF_8)) ==
            report.metadataSha256)
        result
    }.getOrNull()

    fun saveFinished(transcript: Transcript, stats: ProcessingStats) =
        JobFiles.save(File(directory, "finished.checkpoint.json"), Finished(transcript, stats))

    fun worker(
        stage: WorkerStage,
        inputWav: String,
        inputSha256: String,
        dependencies: List<String>,
        plan: ChunkPlan?,
    ): Pair<WorkerRequest, WorkerResult?> {
        val key = sha256((listOf(stage.name, inputWav, inputSha256) + dependencies).joinToString("\n").toByteArray())
        val new = WorkerRequest(stage, directory.absolutePath, inputWav, resumeKey = key)
        val old = runCatching { JobFiles.load<WorkerRequest>(stage.requestFile(directory)) }.getOrNull()
        if (old == null || old.resumeKey != key || old.inputWav != inputWav || old.stage != stage ||
            old.jobDir != directory.absolutePath) return new to null
        val result = runCatching { JobFiles.load<WorkerResult>(stage.resultFile(directory)) }.getOrNull()
        if (result == null || result.requestId != old.requestId || result.stage != stage) return new to null
        val verified = verify(result, inputWav, plan)
        return if (verified == null) new to null else old to verified
    }

    private fun verify(result: WorkerResult, inputWav: String, plan: ChunkPlan?): WorkerResult? = runCatching {
        val completed = result.completed && result.error == null
        when (result.stage) {
            WorkerStage.DENOISE -> {
                require(completed)
                val output = File(directory, "denoised.wav")
                require(PcmWave.inspect(output).sampleCount == PcmWave.inspect(File(directory, inputWav)).sampleCount)
                require(result.outputSha256 == JobFiles.sha256(output))
                result
            }
            WorkerStage.DIARIZE -> {
                require(completed)
                val durationMs = PcmWave.inspect(File(directory, inputWav)).sampleCount / 16
                require(result.turns.all { it.speaker >= 0 && it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= durationMs })
                result
            }
            WorkerStage.VOICEPRINT -> result.takeIf { completed && it.voices.keys.all { id -> id >= 0 } }
            WorkerStage.GIGAAM, WorkerStage.WHISPER -> {
                val chunks = requireNotNull(plan).chunks.associateBy { it.id }
                val source = if (result.stage == WorkerStage.GIGAAM) AsrSource.GIGAAM else AsrSource.WHISPER
                val refs = result.candidates.distinctBy { it.chunkId }.filter { ref ->
                    ref.source == source.wireName && chunks[ref.chunkId]?.let { verifyCandidate(ref, it) } == true
                }
                result.copy(candidates = refs, completed = completed && refs.size == result.candidates.size)
            }
            WorkerStage.SELECT -> {
                val chunks = requireNotNull(plan).chunks.associateBy { it.id }
                val choices = result.choices.distinctBy { it.chunkId }.filter { choice ->
                    val chunk = chunks[choice.chunkId]
                    chunk != null && choice.candidate.chunkId == choice.chunkId && verifyChoice(choice, chunk)
                }
                result.copy(choices = choices, completed = completed && choices.size == result.choices.size)
            }
            WorkerStage.EMOTION -> {
                val targets = JobFiles.load<List<UtteranceEmotionTarget>>(File(directory, "emotion-targets.json"))
                    .associateBy { it.index }
                val emotions = result.emotions.distinctBy { it.target.index }
                    .filter { targets[it.target.index] == it.target }
                result.copy(emotions = emotions, completed = completed && emotions.size == result.emotions.size)
            }
        }
    }.getOrNull()

    private fun verifyChoice(choice: ChoiceRef, chunk: AudioChunk): Boolean = runCatching {
        val ref = choice.candidate
        require(verifyCandidate(ref, chunk))
        val audit = JobFiles.load<SelectionAudit>(File(directory, "selection/${chunk.id}/audit.json"))
        val status = AsrSelectionStatus.valueOf(choice.status)
        require(audit.chunkId == chunk.id && audit.inputSha256 == chunk.inputSha256 &&
            audit.selectedSha256 == ref.sha256 && audit.reason == choice.reason &&
            audit.source.wireName == ref.source && audit.selectedByLlm == (status == AsrSelectionStatus.SELECTED_BY_LLM))
    }.isSuccess

    private fun verifyCandidate(ref: CandidateRef, chunk: AudioChunk): Boolean = runCatching {
        ImmutableAsrCandidate.load(JobFiles.inside(directory, ref.file), ref.sha256)
            .verifyIdentity(chunk.id, chunk.inputSha256, AsrSource.entries.single { it.wireName == ref.source })
    }.isSuccess

    companion object {
        fun archiveRetryOutput(job: File, output: File) {
            if (!output.exists()) return
            val relative = output.relativeTo(job).invariantSeparatorsPath
            require(output.canonicalFile == JobFiles.inside(job, relative))
            val archive = JobFiles.inside(job, "interrupted/${UUID.randomUUID()}/$relative")
            val parent = requireNotNull(archive.parentFile)
            check(parent.mkdirs() || parent.isDirectory)
            Files.move(output.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }

        fun open(root: File, jobId: String, source: File, models: QualityManifest, speakerCount: Int?): JobCheckpoint {
            require(UUID.fromString(jobId).toString() == jobId) { "Invalid transcription job ID" }
            val directory = File(root.canonicalFile, jobId)
            require(directory == directory.canonicalFile) { "Invalid transcription job path" }
            require(source.isFile) { "Recording file is unavailable" }
            val identity = Identity(audioSha256 = JobFiles.sha256(source), models = models, speakerCount = speakerCount)
            val journal = File(directory, "resume.json")
            if (journal.exists()) {
                if (JobFiles.load<Identity>(journal) != identity) {
                    throw IOException("Recording or model pack changed since this transcription was started")
                }
            } else {
                check(!directory.exists() || directory.list().orEmpty().all {
                    it.startsWith(".artifact-") && it.endsWith(".pending")
                })
                JobFiles.save(journal, identity)
            }
            return JobCheckpoint(directory)
        }
    }
}
