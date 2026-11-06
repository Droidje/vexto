package com.vexto.engine.diarization

import com.vexto.engine.model.SpeakerTurn
import com.vexto.engine.model.VoiceEmbedding
import kotlin.math.sqrt

/** Keep the final cluster's normalized centroid; never mix speaker labels across files. */
internal object SpeakerSignatures {
    fun build(modelId: String, embeddings: List<FloatArray>, weights: FloatArray,
              labels: IntArray, turns: List<SpeakerTurn>): Map<Int, VoiceEmbedding> {
        require(embeddings.size == weights.size && weights.size == labels.size)
        if (embeddings.isEmpty()) return emptyMap()
        val dimension = embeddings.first().size
        require(dimension in 1..4096)
        require(embeddings.all { it.size == dimension && it.all(Float::isFinite) })
        require(weights.all { it.isFinite() && it > 0 })
        return labels.distinct().mapNotNull { label ->
            val members = labels.indices.filter { labels[it] == label }
            val centroid = DoubleArray(dimension)
            for (i in members) for (d in centroid.indices) centroid[d] += embeddings[i][d] * weights[i].toDouble()
            val norm = sqrt(centroid.sumOf { it * it })
            val speechMs = turns.filter { it.speaker == label }.sumOf { it.endMs - it.startMs }
            if (norm <= 1e-9 || speechMs <= 0) null else label to VoiceEmbedding(
                modelId, centroid.map { (it / norm).toFloat() }, speechMs, members.size)
        }.toMap()
    }
}
