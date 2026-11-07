package com.vexto.engine.quality

import java.io.File
import kotlinx.serialization.Serializable
import com.vexto.engine.model.VoiceEmbedding
import com.vexto.engine.pipeline.UtteranceEmotionEvidence

@Serializable enum class WorkerStage { DENOISE, DIARIZE, VOICEPRINT, GIGAAM, WHISPER, SELECT, EMOTION }
@Serializable data class WorkerRequest(
    val stage: WorkerStage,
    val jobDir: String,
    val inputWav: String,
    val requestId: String = java.util.UUID.randomUUID().toString(),
    val resumeKey: String? = null,
)
@Serializable data class CandidateRef(val chunkId: String, val source: String, val file: String, val sha256: String)
@Serializable data class ChoiceRef(val chunkId: String, val candidate: CandidateRef, val status: String, val reason: String)
@Serializable data class PipelineTurn(val speaker: Int, val startMs: Long, val endMs: Long)
@Serializable data class WorkerResult(
    val stage: WorkerStage,
    val completed: Boolean = false,
    val requestId: String = "",
    val elapsedMs: Long = 0,
    val peakCombinedRssBytes: Long = 0,
    val candidates: List<CandidateRef> = emptyList(),
    val choices: List<ChoiceRef> = emptyList(),
    val turns: List<PipelineTurn> = emptyList(),
    val voices: Map<Int, VoiceEmbedding> = emptyMap(),
    val emotions: List<UtteranceEmotionEvidence> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val outputSha256: String? = null,
)
internal fun WorkerStage.requestFile(job: File) = File(job, "stages/$name.request.json")
internal fun WorkerStage.resultFile(job: File) = File(job, "stages/$name.result.json")
