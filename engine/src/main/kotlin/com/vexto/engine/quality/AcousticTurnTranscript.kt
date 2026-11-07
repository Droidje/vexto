package com.vexto.engine.quality

import com.vexto.engine.model.Speaker
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.Utterance
import com.vexto.engine.model.Word

/**
 * Puts each diarization interval into the exported JSON with its own start and end,
 * including overlap. Words stay on the single interval they overlap most, so text is not copied.
 * If that move would change the normalized word sequence, the original transcript is kept.
 */
internal object AcousticTurnTranscript {
    fun place(transcript: Transcript, turns: List<PipelineTurn>): Transcript {
        if (turns.isEmpty()) return transcript
        require(turns.all { it.speaker >= 0 && it.endMs > it.startMs && it.startMs >= 0 })
        val words = transcript.utterances.flatMap { it.words }.sortedBy { it.startMs }
        if (words.isEmpty()) return transcript
        val ordered = turns.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.speaker }))
        val buckets = ordered.map { mutableListOf<Word>() }
        val loose = mutableListOf<Word>()
        for (word in words) {
            val index = best(word, ordered)
            if (index == null) loose += word else buckets[index] += word
        }
        val rows = mutableListOf<Utterance>()
        ordered.forEachIndexed { index, turn ->
            val owned = buckets[index]
            rows += Utterance(id(turn.speaker), turn.startMs, turn.endMs, join(owned), owned)
        }
        loose.forEach { word ->
            val end = maxOf(word.endMs, word.startMs + 1)
            rows += Utterance("speaker_unknown", word.startMs, end, word.text, listOf(word))
        }
        val placed = Transcript(
            rows.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.speakerId })),
            rows.map { it.speakerId }.distinct().filter { it != "speaker_unknown" }.map { Speaker(it) },
        )
        return if (tokens(placed) == tokens(transcript)) placed else transcript
    }

    private fun id(speaker: Int) = "speaker_${speaker + 1}"

    private fun join(words: List<Word>): String = words.joinToString(" ") { it.text.trim() }.trim()

    private fun best(word: Word, turns: List<PipelineTurn>): Int? {
        var chosen = -1
        var overlap = 0L
        var start = Long.MAX_VALUE
        turns.forEachIndexed { index, turn ->
            val covered = (minOf(word.endMs, turn.endMs) - maxOf(word.startMs, turn.startMs)).coerceAtLeast(0)
            if (covered > overlap || (covered == overlap && covered > 0 && turn.startMs < start)) {
                chosen = index
                overlap = covered
                start = turn.startMs
            }
        }
        return if (overlap > 0) chosen else null
    }

    private fun tokens(transcript: Transcript): List<String> =
        transcript.utterances.flatMap { utterance ->
            utterance.text.lowercase().replace('ё', 'е')
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .split(' ')
                .filter { it.isNotEmpty() }
        }
}
