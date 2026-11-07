package com.vexto.engine.pipeline

import com.vexto.engine.model.SpeakerTurn
import kotlinx.serialization.Serializable
import java.util.TreeMap

@Serializable
data class EmotionEvidence(
    val speakerId: Int,
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val confidence: Float,
    val probabilities: Map<String, Float>,
    val source: String = "model_prediction_not_true_emotion",
    val selection: String = "first_clean_2_to_8_second_utterance",
    val calibrationValidated: Boolean = false,
    val wholeConversationMood: Boolean = false,
)

internal object EmotionSelection {
    data class Clip(val speakerId: Int, val startMs: Long, val endMs: Long)

    /** Sweep annotated intervals: only one distinct active speaker is eligible. */
    fun select(turns: List<SpeakerTurn>, durationMs: Long): List<Clip> {
        val events = TreeMap<Long, MutableList<Pair<Int,Int>>>()
        for (turn in turns) {
            require(turn.startMs >= 0 && turn.endMs > turn.startMs && turn.endMs <= durationMs)
            events.getOrPut(turn.startMs) { mutableListOf() }.add(turn.speaker to 1)
            events.getOrPut(turn.endMs) { mutableListOf() }.add(turn.speaker to -1)
        }
        val clean = mutableListOf<Clip>()
        val active = mutableMapOf<Int,Int>()
        val times = events.keys.toList()
        for (i in 0 until maxOf(0, times.size-1)) {
            val start = times[i]; val end = times[i+1]
            for ((speaker,delta) in events.getValue(start)) {
                val count = (active[speaker] ?: 0)+delta
                if (count == 0) active.remove(speaker) else active[speaker] = count
            }
            if (active.size != 1 || end <= start) continue
            val speaker = active.keys.single()
            val previous = clean.lastOrNull()
            if (previous?.speakerId == speaker && previous.endMs == start) {
                clean[clean.lastIndex] = previous.copy(endMs = end)
            } else clean.add(Clip(speaker,start,end))
        }
        return turns.sortedBy { it.startMs }.map { it.speaker }.distinct().take(8).mapNotNull { id ->
            clean.firstOrNull { it.speakerId == id && it.endMs-it.startMs >= 2000 }?.let {
                it.copy(endMs = minOf(it.endMs,it.startMs+8000))
            }
        }
    }
}
