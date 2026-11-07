package com.vexto.engine.review

/** Byte limits bound Qwen's byte-level tokenizer; the native worker also checks exact tokens. */
internal class SummaryPlan(transcript: String) {
    val language = ConversationLanguage.detect(transcript)
    val chunks = split(transcript.lineSequence().joinToString("\n", transform = ConversationLanguage::dialogueLine).trim())
        .filter { it.isNotBlank() }
    val steps = chunks.size * 2 - 1

    suspend fun run(summarize: suspend (String) -> String): String {
        val question = if (language.usesCyrillic) "Краткий смысл всей истории:"
            else "What is the central event or topic and its outcome?"
        var level = chunks.map { summarize("$it\n\n$question") }
        // Preserve the opening context when later excerpts discuss only specific details.
        val opening = prefix(chunks.first(), 240).trimEnd()
        val context = if (language.usesCyrillic) "Начало разговора:\n$opening\n\nСодержание частей:"
            else "Conversation opening:\n$opening\n\nSummaries of its parts:"
        while (level.size > 1) {
            level = level.chunked(2).map { pair ->
                if (pair.size == 1) pair.single()
                else {
                    val combined = pair.joinToString("\n\n") { prefix(it, 500).trimEnd() }
                    summarize("$context\n$combined\n\n$question")
                }
            }
        }
        return level.single()
    }

    companion object {
        const val OutputBytes = 640
        private const val ChunkBytes = 1300

        fun prefix(text: String, maxBytes: Int): String {
            var index = 0
            var bytes = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                bytes += utf8Size(codePoint)
                if (bytes > maxBytes) break
                index += Character.charCount(codePoint)
            }
            return text.substring(0, index)
        }

        fun suffix(text: String, maxBytes: Int): String {
            var index = text.length
            var bytes = 0
            while (index > 0) {
                val codePoint = text.codePointBefore(index)
                bytes += utf8Size(codePoint)
                if (bytes > maxBytes) break
                index -= Character.charCount(codePoint)
            }
            return text.substring(index)
        }

        private fun utf8Size(codePoint: Int): Int = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }

        private fun split(text: String): List<String> {
            require(text.isNotBlank())
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var index = start
                var bytes = 0
                var wordEnd = start
                var lineEnd = start
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    val size = utf8Size(codePoint)
                    if (bytes + size > ChunkBytes) break
                    index += Character.charCount(codePoint)
                    bytes += size
                    if (bytes >= ChunkBytes * 3 / 4 && Character.isWhitespace(codePoint)) {
                        wordEnd = index
                        if (codePoint == '\n'.code) lineEnd = index
                    }
                }
                val end = if (index == text.length) index else when {
                    lineEnd > start -> lineEnd
                    wordEnd > start -> wordEnd
                    else -> index
                }
                chunks += text.substring(start, end)
                start = end
            }
            return chunks
        }
    }
}
