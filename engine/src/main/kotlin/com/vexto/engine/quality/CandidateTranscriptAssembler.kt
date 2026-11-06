package com.vexto.engine.quality

import com.vexto.engine.model.*
import com.vexto.engine.review.ImmutableAsrCandidate

/** Derived speaker view. Original strings, tokens and times stay in immutable ASR artifacts. */
class CandidateTranscriptAssembler {
    data class Result(val transcript: Transcript, val estimatedTimingChunks: List<String>)
    fun assemble(plan: ChunkPlan, candidates: Map<String, ImmutableAsrCandidate>, turns: List<PipelineTurn>): Result {
        val utterances = mutableListOf<Utterance>()
        val estimated = mutableListOf<String>()
        for (chunk in plan.chunks) {
            val candidate = candidates[chunk.id] ?: continue
            val text = candidate.text
            val spans = Regex("\\S+").findAll(text).toList()
            if (spans.isEmpty()) continue
            val timings = if (candidate.words.size == spans.size && candidate.words.map { it.text } == spans.map { it.value }) candidate.words else {
                estimated += chunk.id
                TokenWords.spread(text, 0, chunk.durationMs)
            }
            val words = spans.mapIndexed { index, match ->
                val timed = timings[index]
                val start = timed.startMs.coerceIn(0, maxOf(0, chunk.durationMs - 1))
                val end = timed.endMs.coerceIn(start + 1, maxOf(start + 1, chunk.durationMs))
                Word(match.value, chunk.startMs + start, chunk.startMs + end)
            }
            val labels = words.map { label(it, turns) }
            var first = 0
            for (i in 1..words.size) {
                if (i == words.size || labels[i] != labels[first]) {
                    val fromCharacter = if (first == 0) 0 else spans[first].range.first
                    val toCharacter = if (i == words.size) text.length else spans[i].range.first
                    utterances += Utterance(id(labels[first]), words[first].startMs, words.subList(first, i).maxOf { it.endMs },
                        text.substring(fromCharacter, toCharacter), words.subList(first, i).toList())
                    first = i
                }
            }
        }
        val speakerIds = utterances.map { it.speakerId }.distinct()
        return Result(Transcript(utterances, speakerIds.map { Speaker(it) }), estimated)
    }

    private fun id(label: Int): String = if (label < 0) "speaker_unknown" else "speaker_${label + 1}"

    /** Union overlap per speaker: duplicate/fragmented RTTM turns cannot inflate evidence. */
    internal fun label(word: Word, turns: List<PipelineTurn>): Int {
        val scores = turns.filter { it.startMs < word.endMs && it.endMs > word.startMs }
            .groupBy { it.speaker }.mapValues { (_, list) ->
                var end = word.startMs
                var sum = 0L
                list.sortedBy { it.startMs }.forEach { turn ->
                    val a = maxOf(word.startMs, turn.startMs, end)
                    val b = minOf(word.endMs, turn.endMs)
                    if (b > a) sum += b - a
                    end = maxOf(end, b)
                }
                sum
            }
        if (scores.isEmpty()) return -1
        return scores.entries.sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key }).first().key
    }
}
