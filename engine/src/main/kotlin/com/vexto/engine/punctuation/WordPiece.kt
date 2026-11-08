package com.vexto.engine.punctuation

/**
 * WordPiece как у BertTokenizer: самое длинное совпадение в словаре,
 * продолжение слова с префиксом `##`. Незнакомое слово целиком становится `[UNK]`.
 */
internal class WordPiece(
    private val vocab: Map<String, Int>,
    private val continuingPrefix: String,
    private val maxChars: Int,
) {
    val cls: Int = required("[CLS]")
    val sep: Int = required("[SEP]")
    val unk: Int = required("[UNK]")

    fun encodeWord(word: String): IntArray {
        if (word.isEmpty() || word.length > maxChars) return intArrayOf(unk)
        var start = 0
        val ids = ArrayList<Int>(4)
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val piece = if (start == 0) word.substring(start, end) else continuingPrefix + word.substring(start, end)
                val id = vocab[piece]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found < 0) return intArrayOf(unk)
            ids += found
            start = end
        }
        return ids.toIntArray()
    }

    private fun required(token: String): Int =
        vocab[token] ?: throw IllegalArgumentException("В словаре RUPunct нет $token")
}
