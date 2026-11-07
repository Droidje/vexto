package com.vexto.engine.model

/**
 * Сохранённый текст держит фонетическую запись. Экран и экспорт показывают
 * обычную запись тех же звуков.
 *
 * Заполненные паузы: ə, ɐ, ɜ и гортанная смычка ˀ/ʔ → «э», носовой ɯ → «м».
 * Скобки только со вдохом ɥ становятся «вдох»; в смешанной паузе вроде (ɐɯɥ)
 * остаётся звучащая часть «эм». Жест `#пеньк-пеньк#` раскрывается в слоги через пробел.
 */
object PhoneticSurface {
    private val pause = mapOf(
        'ə' to 'э',
        'ɐ' to 'э',
        'ɜ' to 'э',
        'ˀ' to 'э',
        'ʔ' to 'э',
        'ɯ' to 'м',
    )

    @JvmStatic
    fun present(text: String): String {
        if (!containsMark(text)) return text
        val gestures = GESTURE.replace(text) { match ->
            match.groupValues[1].trim('-', ' ').replace(HYPHENS, " ")
        }
        return mapRun(PAREN.replace(gestures) { match ->
            val inner = match.groupValues[1]
            when {
                inner.isEmpty() -> match.value
                inner.all { it == 'ɥ' || it.isWhitespace() } -> "вдох"
                inner.all { it in pause || it == 'ɥ' || it.isWhitespace() } ->
                    buildString(inner.length) { inner.forEach { pause[it]?.let(::append) } }
                else -> "(" + mapRun(inner) + ")"
            }
        })
    }

    private fun mapRun(text: String): String = buildString(text.length) {
        text.forEach { append(pause[it] ?: it) }
    }

    private fun containsMark(text: String): Boolean {
        if ('#' in text) return true
        return text.any { it in pause || it == 'ɥ' }
    }

    private val GESTURE = Regex("#([^#\\n]+)#")
    private val PAREN = Regex("\\(([^)]*)\\)")
    private val HYPHENS = Regex("-+")
}
