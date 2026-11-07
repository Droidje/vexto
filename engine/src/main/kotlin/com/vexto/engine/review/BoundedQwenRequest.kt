package com.vexto.engine.review

import java.io.File

/** Same immutable native configuration for ASR selection and speaker names: one warm model/context. */
internal object BoundedQwenRequest {
    const val MaxPromptBytes = 864
    fun arguments(model: File, request: SelectorRequest, dir: File): List<String> {
        require(request.prompt.toByteArray(Charsets.UTF_8).size <= MaxPromptBytes)
        dir.mkdirs()
        val prompt = File(dir, "prompt.txt").apply { writeText(request.prompt) }
        val schema = File(dir, "tool-schema.json").apply { writeText(request.schema) }
        val template = File(dir, "nonthinking.jinja").apply { writeText(SelectorPrompt.NonThinkingTemplate) }
        return listOf(
            "-m", model.absolutePath, "--jinja", "--chat-template-file", template.absolutePath,
            "--reasoning", "off", "-cnv", "--single-turn", "--simple-io", "--no-display-prompt",
            "--no-context-shift", "-c", "1024", "-t", "2", "--threads-batch", "2",
            "-b", "128", "-ub", "64", "-ngl", "0", "--no-repack", "--no-warmup",
            "--cache-type-k", "f16", "--cache-type-v", "f16", "--seed", "42",
            "--temp", "0", "--repeat-penalty", "1.0", "-n", "16",
            "--json-schema-file", schema.absolutePath, "-f", prompt.absolutePath,
        )
    }
}
