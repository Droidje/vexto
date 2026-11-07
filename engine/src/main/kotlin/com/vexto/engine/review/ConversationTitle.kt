package com.vexto.engine.review

internal object ConversationTitle {
    private val wordBreak = Regex("\\s+")
    private val generic = Regex("(разговор|беседа|диалог|conversation|discussion|chat|dialogue)(?:\\s*(?:№|#)?\\s*\\d+)?",
        RegexOption.IGNORE_CASE)

    fun input(plan: SummaryPlan): String {
        val excerpts = plan.chunks
        val content = if (excerpts.size == 1) excerpts.single() else {
            // Sample across the recording so a greeting cannot monopolize the prompt.
            val indices = listOf(0, excerpts.size / 2, excerpts.lastIndex).distinct()
            val bytesPerExcerpt = 1260 / indices.size
            indices.joinToString("\n…\n") { index ->
                val excerpt = excerpts[index]
                (if (index == excerpts.lastIndex) SummaryPlan.suffix(excerpt, bytesPerExcerpt)
                else SummaryPlan.prefix(excerpt, bytesPerExcerpt)).trim()
            }
        }
        val question = if (plan.language.usesCyrillic) "Название разговора:" else "Conversation title:"
        return "$content\n\n$question"
    }

    fun normalize(output: String): String {
        val title = output.trim().removeSuffix(".").trim().trim('"', '\'', '«', '»', '“', '”')
            .trim().removeSuffix(".").trim()
        require(title.isNotEmpty() && title.length <= 80 && title.any(Char::isLetter) &&
            title.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } &&
            title.split(wordBreak).size <= 8 && !generic.matches(title) && ':' !in title) {
            "Model did not return a specific conversation title"
        }
        return title.replaceFirstChar { it.titlecase() }
    }
}
