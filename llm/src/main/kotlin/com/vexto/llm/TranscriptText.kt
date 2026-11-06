package com.vexto.llm

/** Текстовое представление транскрипта для промптов: `[mm:ss] speaker_N: text`. */
internal object TranscriptText {

    fun timestamp(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    fun line(u: Utterance): String = "[${timestamp(u.startMs)}] ${u.speakerId}: ${u.text.trim()}"

    fun format(utterances: List<Utterance>): String = utterances.joinToString("\n") { line(it) }

    /**
     * Делит транскрипт на куски, каждый из которых помещается в [budgetTokens].
     * [counts]: число токенов в строке [line] каждой реплики.
     * Реплика длиннее бюджета не режется посередине, а уходит отдельным куском:
     * контекст модели всё равно рассчитан с запасом.
     */
    fun chunk(
        utterances: List<Utterance>,
        budgetTokens: Int,
        counts: IntArray,
    ): List<List<Utterance>> {
        if (utterances.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<Utterance>>()
        var current = mutableListOf<Utterance>()
        var used = 0
        utterances.forEachIndexed { i, u ->
            if (current.isNotEmpty() && used + counts[i] > budgetTokens) {
                chunks += current
                current = mutableListOf()
                used = 0
            }
            current += u
            used += counts[i]
        }
        chunks += current
        return chunks
    }
}
