package com.vexto.engine.model

import kotlinx.serialization.Serializable

/** Слово с таймкодами в миллисекундах от начала записи. */
@Serializable
data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/** Интервал речи одного говорящего по данным диаризации. */
data class SpeakerTurn(
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
)

/** Реплика: непрерывная речь одного говорящего. */
@Serializable
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word> = emptyList(),
    val emotionProbabilities: EmotionProbabilities? = null,
)

/** Участник разговора. [id] стабилен и уходит в экспорт, [name] — контекстная подсказка LLM или имя, заданное пользователем. */
@Serializable
data class Speaker(
    val id: String,
    val name: String? = null,
    val voice: VoiceEmbedding? = null,
) {
    fun displayName(): String = name ?: "Спикер ${id.substringAfterLast('_')}"
}

@Serializable
data class Transcript(
    val utterances: List<Utterance>,
    val speakers: List<Speaker>,
) {
    fun speaker(id: String): Speaker = speakers.firstOrNull { it.id == id } ?: Speaker(id)

    /** Суммарное время речи каждого участника. */
    fun talkTimeMs(): Map<String, Long> =
        utterances.groupBy { it.speakerId }.mapValues { (_, list) -> list.sumOf { it.endMs - it.startMs } }

    companion object {
        val Empty = Transcript(emptyList(), emptyList())
    }
}
