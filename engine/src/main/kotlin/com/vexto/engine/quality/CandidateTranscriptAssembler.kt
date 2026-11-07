package com.vexto.engine.quality

import com.vexto.engine.model.*
import com.vexto.engine.review.ImmutableAsrCandidate

/** Derived speaker view. Original strings, tokens and times stay in immutable ASR artifacts. */
class CandidateTranscriptAssembler {
    data class Result(val transcript: Transcript, val estimatedTimingChunks: List<String>)
    fun assemble(plan: ChunkPlan, candidates: Map<String, ImmutableAsrCandidate>, turns: List<PipelineTurn>): Result {
        plan.validate()
        require(turns.all { it.speaker >= 0 && it.startMs >= 0 && it.endMs > it.startMs })
        val utterances = mutableListOf<Utterance>()
        val estimated = mutableListOf<String>()
        for (chunk in plan.chunks) {
            val candidate = candidates[chunk.id] ?: continue
            candidate.verifyIdentity(chunk.id, chunk.inputSha256, candidate.source)
            val text = candidate.text
            val spans = WordSpans.findAll(text).toList()
            if (spans.isEmpty()) continue
            val durationMs = maxOf(1L, chunk.durationMs)
            var approximate = chunk.durationMs <= 0
            val matchingOrderedTimes = candidate.words.size == spans.size &&
                candidate.words.map { it.text } == spans.map { it.value } &&
                candidate.words.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }
            val timings = if (matchingOrderedTimes) candidate.words else {
                approximate = true
                spread(spans, durationMs)
            }
            val words = spans.mapIndexed { index, match ->
                val timed = timings[index]
                val start = timed.startMs.coerceIn(0, durationMs - 1)
                val end = timed.endMs.coerceIn(start + 1, durationMs)
                if (start != timed.startMs || end != timed.endMs) approximate = true
                Word(match.value, chunk.startMs + start, chunk.startMs + end)
            }
            if (approximate) estimated += chunk.id
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

    private fun id(label: Int): String = if (label < 0) "speaker_unknown" else "speaker_${label.toLong() + 1}"

    /** Use the same Unicode word spans for timing and slicing; never split only on ASCII spaces. */
    private fun spread(spans: List<MatchResult>, durationMs: Long): List<Word> {
        val total = spans.sumOf { it.value.length.toLong() + 1 }
        var characters = 0L
        return spans.map { span ->
            val start = durationMs * characters / total
            characters += span.value.length + 1L
            Word(span.value, start, durationMs * characters / total)
        }
    }

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
    private companion object {
        val WordSpans = Regex("[^\\s\\p{Z}\\u0085\\u001c-\\u001f]+")
    }
}
