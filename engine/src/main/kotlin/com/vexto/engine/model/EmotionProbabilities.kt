package com.vexto.engine.model

import kotlin.math.abs

/** Estimated from one utterance's audio. Storage and design order: neutral, joy, sadness, anger. */
data class EmotionProbabilities(val neutral: Double, val joy: Double, val sadness: Double, val anger: Double) {
    init {
        require(neutral in 0.0..1.0 && joy in 0.0..1.0 && sadness in 0.0..1.0 && anger in 0.0..1.0 &&
            abs(neutral + joy + sadness + anger - 1.0) <= 0.001) { "Invalid emotion probabilities" }
    }

    fun ordered(): List<Double> = listOf(neutral, joy, sadness, anger)

    fun strongest(): Emotion {
        var best = Emotion.Neutral
        var score = neutral
        if (joy > score) { best = Emotion.Joy; score = joy }
        if (sadness > score) { best = Emotion.Sadness; score = sadness }
        return if (anger > score) Emotion.Anger else best
    }

    companion object {
        /** The pinned GigaAM-Emo export emits angry, sad, neutral, positive. */
        fun fromModel(scores: FloatArray): EmotionProbabilities {
            require(scores.size == 4) { "Expected four GigaAM-Emo scores" }
            return EmotionProbabilities(scores[2].toDouble(), scores[3].toDouble(),
                scores[1].toDouble(), scores[0].toDouble())
        }
    }
}

enum class Emotion { Neutral, Joy, Sadness, Anger }
