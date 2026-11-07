package com.vexto.engine.punctuation

import java.util.Locale

/**
 * Восстановление знаков и регистра по меткам RUPunct.
 * Буквы и цифры исходных слов не заменяются: модель только выбирает регистр и знак после слова.
 */
internal object RuPunctText {
    const val MaxPieces = 158

    private val Russian = Locale.forLanguageTag("ru")

    /** Порядок совпадает с id2label RUPunct_small. */
    val labels: List<String> = listOf(
        "UPPER_PERIOD", "LOWER_PERIOD", "UPPER_TOTAL_PERIOD",
        "UPPER_COMMA", "LOWER_COMMA", "UPPER_TOTAL_COMMA",
        "UPPER_QUESTION", "LOWER_QUESTION", "UPPER_TOTAL_QUESTION",
        "UPPER_TIRE", "LOWER_TIRE", "UPPER_TOTAL_TIRE",
        "UPPER_VOSKL", "LOWER_VOSKL", "UPPER_TOTAL_VOSKL",
        "UPPER_DVOETOCHIE", "LOWER_DVOETOCHIE", "UPPER_TOTAL_DVOETOCHIE",
        "UPPER_PERIODCOMMA", "LOWER_PERIODCOMMA", "UPPER_TOTAL_PERIODCOMMA",
        "UPPER_DEFIS", "LOWER_DEFIS", "UPPER_TOTAL_DEFIS",
        "UPPER_QUESTIONVOSKL", "LOWER_QUESTIONVOSKL", "UPPER_TOTAL_QUESTIONVOSKL",
        "UPPER_MNOGOTOCHIE", "LOWER_MNOGOTOCHIE", "UPPER_TOTAL_MNOGOTOCHIE",
        "UPPER_O", "LOWER_O", "UPPER_TOTAL_O",
    )

    fun words(text: String): List<String> {
        val found = ArrayList<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) {
                found += word.toString().lowercase(Russian)
                word.clear()
            }
        }
        for (ch in text) {
            if (ch.isLetterOrDigit()) word.append(ch) else flush()
        }
        flush()
        return found
    }

    fun render(token: String, label: String): String {
        val cased = when {
            label.startsWith("UPPER_TOTAL") -> token.uppercase(Russian)
            label.startsWith("UPPER") -> token.replaceFirstChar { it.titlecase(Russian) }
            else -> token
        }
        val kind = when {
            label.startsWith("UPPER_TOTAL_") -> label.removePrefix("UPPER_TOTAL_")
            label.startsWith("UPPER_") -> label.removePrefix("UPPER_")
            label.startsWith("LOWER_") -> label.removePrefix("LOWER_")
            else -> "O"
        }
        val mark = when (kind) {
            "PERIOD" -> "."
            "COMMA" -> ","
            "QUESTION" -> "?"
            "TIRE" -> if (label.startsWith("LOWER")) "—" else " —"
            "VOSKL" -> "!"
            "DVOETOCHIE" -> ":"
            "PERIODCOMMA" -> ";"
            "DEFIS" -> "-"
            "MNOGOTOCHIE" -> "..."
            "QUESTIONVOSKL" -> "?!"
            else -> ""
        }
        return cased + mark
    }

    fun restore(
        text: String,
        pieces: WordPiece,
        maxPieces: Int = MaxPieces,
        classify: (IntArray) -> IntArray,
    ): String {
        val source = words(text)
        if (source.isEmpty()) return text.trim()
        val rendered = ArrayList<String>(source.size)
        var index = 0
        while (index < source.size) {
            val ids = ArrayList<Int>()
            val firstPiece = ArrayList<Int>()
            ids += pieces.cls
            val start = index
            while (index < source.size) {
                val encoded = pieces.encodeWord(source[index])
                if (ids.size - 1 + encoded.size > maxPieces && index > start) break
                firstPiece += ids.size
                encoded.forEach { ids += it }
                index++
                if (ids.size - 1 >= maxPieces) break
            }
            ids += pieces.sep
            val labels = classify(ids.toIntArray())
            if (labels.size != ids.size) throw IllegalStateException("RUPunct вернул ${labels.size} меток на ${ids.size} токенов")
            for (pieceAt in firstPiece) {
                val label = labels.getOrNull(pieceAt)?.let { id -> labelsName(id) } ?: "LOWER_O"
                rendered += render(source[rendered.size], label)
            }
        }
        val result = rendered.joinToString(" ")
        return if (letters(result) == letters(source.joinToString(""))) result else text
    }

    private fun labelsName(id: Int): String = labels.getOrElse(id) { "LOWER_O" }

    private fun letters(text: String): String =
        buildString(text.length) {
            for (ch in text.lowercase(Russian)) if (ch.isLetterOrDigit()) append(ch)
        }
}
