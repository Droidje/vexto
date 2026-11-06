package com.vexto.engine.model

/**
 * Собирает реплики из слов ASR и интервалов диаризации.
 *
 * Каждое слово получает говорящего интервала с наибольшим перекрытием по времени. Слово вне всех
 * интервалов получает ближайший интервал в пределах [maxSnapMs], иначе говорящего
 * предыдущего слова. Соседние слова одного говорящего склеиваются в реплику, пока пауза
 * между ними не больше [maxPauseMs].
 */
class TranscriptAssembler(
    private val maxSnapMs: Long = 1_000,
    private val maxPauseMs: Long = 5_000,
) {

    fun assemble(words: List<Word>, turns: List<SpeakerTurn>): Transcript {
        if (words.isEmpty()) return Transcript.Empty
        val sortedWords = words.sortedBy { it.startMs }
        val sortedTurns = turns.sortedBy { it.startMs }

        val labels = IntArray(sortedWords.size)
        var previous = sortedTurns.firstOrNull()?.speaker ?: 0
        sortedWords.forEachIndexed { i, word ->
            val label = bestOverlap(word, sortedTurns) ?: nearest(word, sortedTurns) ?: previous
            labels[i] = label
            previous = label
        }

        val ids = HashMap<Int, String>()
        fun idOf(label: Int) = ids.getOrPut(label) { "speaker_${ids.size + 1}" }

        val utterances = mutableListOf<Utterance>()
        var start = 0
        for (i in 1..sortedWords.size) {
            val boundary = i == sortedWords.size ||
                labels[i] != labels[start] ||
                sortedWords[i].startMs - sortedWords[i - 1].endMs > maxPauseMs
            if (boundary) {
                val chunk = sortedWords.subList(start, i)
                utterances += Utterance(
                    speakerId = idOf(labels[start]),
                    startMs = chunk.first().startMs,
                    endMs = chunk.last().endMs,
                    text = chunk.joinToString(" ") { it.text },
                    words = chunk.toList(),
                )
                start = i
            }
        }
        val speakers = ids.values.sortedBy { it.substringAfterLast('_').toInt() }.map { Speaker(it) }
        return Transcript(utterances, speakers)
    }

    /** При равном перекрытии выигрывает более короткий интервал: так реплика-перебивание не теряется. */
    private fun bestOverlap(word: Word, turns: List<SpeakerTurn>): Int? {
        var best: SpeakerTurn? = null
        var bestOverlap = 0L
        for (turn in turns) {
            if (turn.startMs >= word.endMs) break
            val overlap = minOf(word.endMs, turn.endMs) - maxOf(word.startMs, turn.startMs)
            val shorter = best != null && turn.endMs - turn.startMs < best.endMs - best.startMs
            if (overlap > bestOverlap || (overlap > 0 && overlap == bestOverlap && shorter)) {
                best = turn
                bestOverlap = overlap
            }
        }
        return best?.speaker
    }

    private fun nearest(word: Word, turns: List<SpeakerTurn>): Int? {
        val center = (word.startMs + word.endMs) / 2
        return turns
            .map { it to distance(center, it) }
            .filter { it.second <= maxSnapMs }
            .minByOrNull { it.second }
            ?.first?.speaker
    }

    private fun distance(point: Long, turn: SpeakerTurn): Long = when {
        point < turn.startMs -> turn.startMs - point
        point > turn.endMs -> point - turn.endMs
        else -> 0
    }
}
