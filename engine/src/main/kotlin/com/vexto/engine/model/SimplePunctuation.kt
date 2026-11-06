package com.vexto.engine.model

/**
 * Оформление реплик для ASR без пунктуации: заглавная буква в начале и точка в конце.
 * Слова не меняются, поэтому WER после нормализации остаётся прежним.
 */
object SimplePunctuation {

    private val Terminal = charArrayOf('.', '!', '?', '…')

    fun apply(transcript: Transcript): Transcript =
        transcript.copy(utterances = transcript.utterances.map { it.copy(text = format(it.text)) })

    fun format(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val capitalized = trimmed.replaceFirstChar { it.titlecase() }
        return if (capitalized.last() in Terminal) capitalized else "$capitalized."
    }
}
