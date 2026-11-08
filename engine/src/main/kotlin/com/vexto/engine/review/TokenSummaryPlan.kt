package com.vexto.engine.review

/** Planning and generation share the native model's exact formatted-prompt tokenizer. */
internal class TokenSummaryPlan(transcript: String) {
    val language = ConversationLanguage.detect(transcript)
    val source = transcript.lineSequence().joinToString("\n", transform = ConversationLanguage::dialogueLine).trim()

    suspend fun run(
        fitPrefix: suspend (String, String) -> Int,
        fits: suspend (String) -> Boolean,
        summarize: suspend (String) -> String,
        progress: suspend (Int, Int) -> Unit,
    ): String {
        require(source.isNotBlank())
        val question = if (language.usesCyrillic) "Краткий смысл всей истории:"
            else "What is the central event or topic and its outcome?"
        val mergeQuestion = if (language.usesCyrillic) "Итог с учётом поздних исправлений:"
            else "Summarize the whole; later corrections supersede earlier plans."
        val inputs = mutableListOf<String>()
        var at = 0
        while (at < source.length) {
            progress(-1, 0)
            // Transport bound only; the actual chunk boundary comes from the tokenizer.
            val available = SummaryPlan.prefix(source.substring(at), 32000)
            val suffix = "\n\n$question"
            val bytes = fitPrefix(available, suffix)
            require(bytes > 0 && bytes <= available.toByteArray().size) { "No source text fits the model context" }
            val prefix = available.toByteArray().copyOf(bytes)
            val part = prefix.toString(Charsets.UTF_8)
            require(part.toByteArray().contentEquals(prefix) && available.startsWith(part)) { "Invalid native UTF-8 boundary" }
            inputs += part + suffix
            at += part.length
        }
        var done = 0
        // Merge count is data-dependent. Reserve the maximum remaining calls, then
        // replace it with an exact count when each next level has been packed.
        var total = inputs.size * 2 - 1
        progress(0, total)
        suspend fun generate(input: String): String {
            val result = summarize(input)
            done++
            progress(done, total)
            return result
        }
        var level = inputs.map { generate(it) }
        while (level.size > 1) {
            val packed = mutableListOf<Pair<String?, String?>>()
            var index = 0
            while (index < level.size) {
                var selected = 0
                var input = ""
                // Descending search is intentional: BPE counts are not monotone.
                for (size in level.size - index downTo 2) {
                    val candidate = level.subList(index, index + size).mapIndexed { i, text ->
                        "${i + 1}:\n$text"
                    }.joinToString("\n\n") + "\n\n$mergeQuestion"
                    if (candidate.toByteArray().size <= 32768 && fits(candidate)) {
                        selected = size
                        input = candidate
                        break
                    }
                }
                if (selected == 0) {
                    require(index == level.lastIndex) { "Two complete summaries do not fit the context" }
                    packed += null to level[index]
                    index++
                } else {
                    packed += input to null
                    index += selected
                }
            }
            total = done + packed.count { it.first != null } + (packed.size - 1)
            progress(done, total)
            level = packed.map { (input, carry) -> if (input == null) carry!! else generate(input) }
        }
        progress(done, done)
        return level.single()
    }
}
