package com.vexto.llm

/** Реплика транскрипта: тот же набор полей, что и в экспорте движка распознавания. */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** Пункт сводки со ссылкой на момент записи, который точно есть в транскрипте. */
data class SummaryPoint(
    val timestamp: String,
    val startMs: Long,
    val text: String,
)

data class Summary(
    val text: String,
    val points: List<SummaryPoint>,
)
