package com.vexto.engine.pipeline

import com.vexto.engine.model.EmotionProbabilities
import com.vexto.engine.model.Transcript
import kotlinx.serialization.Serializable

/** Identity of the exact assembled line; no ASR text is sent to the emotion worker. */
@Serializable data class UtteranceEmotionTarget(
    val index: Int,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
) {
    init { require(index >= 0 && speakerId.isNotBlank() && startMs >= 0 && endMs >= startMs) }
}

@Serializable data class UtteranceEmotionEvidence(
    val target: UtteranceEmotionTarget,
    /** UI/storage order: neutral, joy, sadness, anger. Not native model order. */
    val probabilities: List<Double>,
    val windows: Int,
) {
    init { require(windows > 0); asProbabilities() }
    fun asProbabilities(): EmotionProbabilities {
        require(probabilities.size == 4)
        return EmotionProbabilities(probabilities[0], probabilities[1], probabilities[2], probabilities[3])
    }
}

@Serializable data class UtteranceEmotionArtifact(
    val utterances: List<UtteranceEmotionEvidence>,
    val model: String = "GigaAM-Emo-v1-third-party-dynamic-int8",
    val source: String = "model_prediction_not_true_emotion",
    val selection: String = "exact_utterance_original_audio_bounded_8s_windows",
    val overlapChecked: Boolean = false,
    val calibrationValidated: Boolean = false,
    val wholeConversationMood: Boolean = false,
)

object UtteranceEmotionAnnotations {
    fun targets(transcript: Transcript): List<UtteranceEmotionTarget> = transcript.utterances.mapIndexed { i, line ->
        UtteranceEmotionTarget(i, line.speakerId, line.startMs, line.endMs)
    }

    /** Reject stale/duplicate identity before attaching any score. Words and text stay byte-for-byte. */
    fun attach(transcript: Transcript, evidence: List<UtteranceEmotionEvidence>): Transcript {
        val targets = targets(transcript)
        require(evidence.map { it.target.index }.distinct().size == evidence.size) { "Duplicate emotion target" }
        evidence.forEach { require(targets.getOrNull(it.target.index) == it.target) { "Emotion target mismatch" } }
        val byIndex = evidence.associateBy { it.target.index }
        return transcript.copy(utterances = transcript.utterances.mapIndexed { i, line ->
            byIndex[i]?.let { line.copy(emotionProbabilities = it.asProbabilities()) } ?: line
        })
    }
}
