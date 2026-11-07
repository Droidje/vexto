package com.vexto.engine.quality

import com.vexto.engine.model.*
import com.vexto.engine.review.ImmutableAsrCandidate

/** Derived speaker view. Original strings, tokens and times stay in immutable ASR artifacts. */
class CandidateTranscriptAssembler {
    data class Result(val transcript: Transcript, val estimatedTimingChunks: List<String>)
    fun assemble(plan: ChunkPlan, candidates: Map<String, ImmutableAsrCandidate>, turns: List<PipelineTurn>): Result {
        plan.validate()
        require(turns.all { it.speaker >= 0 && it.startMs >= 0 && it.endMs > it.startMs })
        val speech = turns.groupBy { it.speaker }.values.flatMap { mergeTurns(it) }
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
            val chunkSpeech = speech.filter { it.startMs < chunk.startMs + durationMs && it.endMs > chunk.startMs }
            val timings = if (matchingOrderedTimes) candidate.words else {
                approximate = true
                val active = mergeTurns(chunkSpeech.map {
                    PipelineTurn(0, maxOf(0L, it.startMs - chunk.startMs),
                        minOf(durationMs, it.endMs - chunk.startMs))
                })
                spread(spans, active.ifEmpty { listOf(PipelineTurn(0, 0, durationMs)) })
            }
            var previousStart = chunk.startMs
            val placed = spans.mapIndexed { index, match ->
                val timed = timings[index]
                // After repairing order, attribute only from speech overlapping the repaired interval.
                val start = timed.startMs.coerceIn(previousStart - chunk.startMs, durationMs - 1)
                val end = timed.endMs.coerceIn(start + 1, durationMs)
                if (start != timed.startMs || end != timed.endMs) approximate = true
                val word = Word(match.value, chunk.startMs + start, chunk.startMs + end)
                val displaced = start > timed.startMs && start >= timed.endMs
                val turn = if (displaced) null else bestTurn(word, chunkSpeech)
                val bounded = if (turn == null) word else word.copy(
                    startMs = maxOf(word.startMs, turn.startMs), endMs = minOf(word.endMs, turn.endMs))
                previousStart = bounded.startMs
                Placement(bounded, turn)
            }
            if (approximate) estimated += chunk.id
            var first = 0
            for (i in 1..placed.size) {
                if (i == placed.size || placed[i].turn != placed[first].turn) {
                    val fromCharacter = if (first == 0) 0 else spans[first].range.first
                    val toCharacter = if (i == placed.size) text.length else spans[i].range.first
                    val words = placed.subList(first, i).map { it.word }
                    utterances += Utterance(id(placed[first].turn?.speaker ?: -1), words.first().startMs,
                        words.maxOf { it.endMs }, text.substring(fromCharacter, toCharacter), words)
                    first = i
                }
            }
        }
        val speakerIds = utterances.map { it.speakerId }.distinct()
        return Result(Transcript(utterances, speakerIds.map { Speaker(it) }), estimated)
    }

    private fun id(label: Int): String = if (label < 0) "speaker_unknown" else "speaker_${label.toLong() + 1}"

    private data class Placement(val word: Word, val turn: PipelineTurn?)

    /** Spread over concatenated speech, then place each word in the continuous interval containing its midpoint. */
    private fun spread(spans: List<MatchResult>, active: List<PipelineTurn>): List<Word> {
        val durationMs = active.sumOf { it.endMs - it.startMs }
        val total = spans.sumOf { it.value.length.toLong() + 1 }
        var characters = 0L
        var offset = 0L
        var interval = 0
        return spans.map { span ->
            val start = durationMs * characters / total
            characters += span.value.length + 1L
            val end = durationMs * characters / total
            val midpoint = minOf(start + (end - start) / 2, durationMs - 1)
            while (midpoint >= offset + active[interval].endMs - active[interval].startMs) {
                offset += active[interval].endMs - active[interval].startMs
                interval++
            }
            val turn = active[interval]
            val from = (turn.startMs + start - offset).coerceIn(turn.startMs, turn.endMs - 1)
            val to = (turn.startMs + end - offset).coerceIn(from + 1, turn.endMs)
            Word(span.value, from, to)
        }
    }

    private fun mergeTurns(turns: List<PipelineTurn>): List<PipelineTurn> {
        val merged = mutableListOf<PipelineTurn>()
        for (turn in turns.sortedBy { it.startMs }) {
            val previous = merged.lastOrNull()
            if (previous != null && turn.startMs <= previous.endMs) {
                merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, turn.endMs))
            } else merged += turn
        }
        return merged
    }

    private fun overlap(word: Word, turn: PipelineTurn): Long =
        (minOf(word.endMs, turn.endMs) - maxOf(word.startMs, turn.startMs)).coerceAtLeast(0L)

    /** Choose speaker by union overlap, then the best continuous interval of that speaker. */
    private fun bestTurn(word: Word, speech: List<PipelineTurn>): PipelineTurn? {
        val overlapping = speech.filter { overlap(word, it) > 0 }
        val speaker = overlapping.groupBy { it.speaker }.entries.minWithOrNull(
            compareByDescending<Map.Entry<Int, List<PipelineTurn>>> { entry -> entry.value.sumOf { overlap(word, it) } }
                .thenBy { it.key }) ?: return null
        return speaker.value.minWithOrNull(compareByDescending<PipelineTurn> { overlap(word, it) }.thenBy { it.startMs })
    }

    internal fun label(word: Word, turns: List<PipelineTurn>): Int =
        bestTurn(word, turns.groupBy { it.speaker }.values.flatMap { mergeTurns(it) })?.speaker ?: -1

    private companion object {
        val WordSpans = Regex("[^\\s\\p{Z}\\u0085\\u001c-\\u001f]+")
    }
}
