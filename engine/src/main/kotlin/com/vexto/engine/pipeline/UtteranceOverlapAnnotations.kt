package com.vexto.engine.pipeline

import com.vexto.engine.model.Transcript
import com.vexto.engine.models.OverlapManifest
import kotlinx.serialization.Serializable

@Serializable data class UtteranceOverlapTarget(
    val index: Int,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
) {
    init { require(index >= 0 && speakerId.isNotBlank() && startMs >= 0 && endMs >= startMs) }
}

@Serializable data class UtteranceOverlapEvidence(
    val target: UtteranceOverlapTarget,
    val maxScore: Double?,
    val windows: Int,
    val modelRevision: String,
) {
    init {
        require(modelRevision.isNotBlank())
        require(windows == OverlapWindows.count(target.startMs, target.endMs))
        require(if (windows == 0) maxScore == null else maxScore != null && maxScore.isFinite() && maxScore in 0.0..1.0)
    }
    val detected: Boolean? get() = maxScore?.let { it >= OverlapManifest.Threshold }
}

/** One centered, contained window per line: no repeated chances for a false alarm on long lines.
 * Speech outside that window is intentionally not classified; short replies are left unknown.
 */
object OverlapWindows {
    fun count(startMs: Long, endMs: Long): Int {
        require(startMs >= 0 && endMs >= startMs)
        return if (endMs - startMs < 4000) 0 else 1
    }

    fun starts(startMs: Long, endMs: Long): Sequence<Long> = sequence {
        if (count(startMs, endMs) == 1) {
            val centeredMs = startMs + (endMs - startMs - 4000) / 2
            yield(Math.multiplyExact(centeredMs, 16))
        }
    }
}

object UtteranceOverlapAnnotations {
    fun targets(transcript: Transcript): List<UtteranceOverlapTarget> = transcript.utterances.mapIndexed { i, line ->
        UtteranceOverlapTarget(i, line.speakerId, line.startMs, line.endMs)
    }

    fun attach(transcript: Transcript, evidence: List<UtteranceOverlapEvidence>, modelRevision: String): Transcript {
        val targets = targets(transcript)
        require(evidence.map { it.target.index }.distinct().size == evidence.size) { "Duplicate overlap target" }
        evidence.forEach {
            require(targets.getOrNull(it.target.index) == it.target && it.modelRevision == modelRevision) { "Overlap identity mismatch" }
        }
        val byIndex = evidence.associateBy { it.target.index }
        return transcript.copy(utterances = transcript.utterances.mapIndexed { index, line ->
            line.copy(overlapDetected = byIndex[index]?.detected)
        })
    }
}
