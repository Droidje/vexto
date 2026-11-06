package com.vexto.engine.model

/**
 * Склейка токенов ASR в слова. Новое слово начинается с токена с префиксом «▁» или пробелом
 * (BPE и посимвольные словари GigaAM). Конец слова: время последнего токена плюс [tailMs],
 * но не позже начала следующего слова и конца сегмента.
 */
object TokenWords {

    private const val TailMs = 400L
    private const val MinWordMs = 80L

    fun group(tokens: Array<String>, timesSec: FloatArray, segmentStartMs: Long, segmentEndMs: Long): List<Word> {
        data class Draft(val text: StringBuilder, val startMs: Long, var lastMs: Long)

        val drafts = mutableListOf<Draft>()
        var newWord = true
        for (i in tokens.indices) {
            val token = tokens[i]
            val t = segmentStartMs + (timesSec[i] * 1000).toLong()
            if (token.startsWith("▁") || token.startsWith(" ")) newWord = true
            val piece = token.replace("▁", "").trim()
            if (piece.isEmpty()) continue
            if (newWord || drafts.isEmpty()) {
                drafts += Draft(StringBuilder(piece), t, t)
                newWord = false
            } else {
                drafts.last().text.append(piece)
                drafts.last().lastMs = t
            }
        }
        return drafts.mapIndexed { i, d ->
            val next = if (i + 1 < drafts.size) drafts[i + 1].startMs else segmentEndMs
            val end = maxOf(minOf(next, d.lastMs + TailMs), d.startMs + MinWordMs)
            Word(d.text.toString(), d.startMs, end)
        }
    }

    /** Запасной вариант без таймкодов токенов: слова делят сегмент пропорционально длине. */
    fun spread(text: String, segmentStartMs: Long, segmentEndMs: Long): List<Word> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val total = words.sumOf { it.length + 1 }
        val span = segmentEndMs - segmentStartMs
        var chars = 0
        return words.map {
            val start = segmentStartMs + span * chars / total
            chars += it.length + 1
            Word(it, start, segmentStartMs + span * chars / total)
        }
    }
}
