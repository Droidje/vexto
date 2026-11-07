package com.vexto.engine.review

/** Byte limits bound Qwen's byte-level tokenizer; the native worker also checks exact tokens. */
internal class SummaryPlan(transcript: String) {
    val chunks = split(transcript).filter { it.isNotBlank() }
    val steps = chunks.size * 2 - 1

    suspend fun run(summarize: suspend (String) -> String): String {
        var level = chunks.map { summarize("Dialogue excerpt:\n$it") }
        while (level.size > 1) {
            level = level.chunked(2).map { pair ->
                if (pair.size == 1) pair.single()
                else summarize("Partial summaries:\n1. ${pair[0]}\n2. ${pair[1]}")
            }
        }
        return level.single()
    }

    companion object {
        const val OutputBytes = 640
        private const val ChunkBytes = 1300

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
                    val size = when {
                        codePoint <= 0x7f -> 1
                        codePoint <= 0x7ff -> 2
                        codePoint <= 0xffff -> 3
                        else -> 4
                    }
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
