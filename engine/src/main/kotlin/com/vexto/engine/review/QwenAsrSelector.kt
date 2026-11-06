package com.vexto.engine.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File

/** Local model + local tool. A valid selection is not an independently validated quality verdict. */
class QwenAsrSelector(
    private val executor: LocalNativeExecutor,
    private val modelFile: File,
    private val tool: AsrSelectionTool = AsrSelectionTool(),
    private val promptBuilder: SelectorPrompt = SelectorPrompt(),
    private val timeoutMs: Long = 120_000,
) {
    suspend fun selectAll(pairs: List<AsrCandidatePair>, workDir: File): List<AsrSelection> {
        require(pairs.map { it.chunkId }.distinct().size == pairs.size) { "duplicate_chunk_id" }
        pairs.forEach { require(it.chunkId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))) }
        workDir.mkdirs()
        // Each child is awaited/reaped before the next one; every input chunk produces a decision.
        return pairs.map { pair -> selectOne(pair, File(workDir, pair.chunkId)) }
    }

    private suspend fun selectOne(pair: AsrCandidatePair, dir: File): AsrSelection {
        dir.mkdirs()
        val selected = File(dir, "display-candidate.json")
        if (pair.gigaam == null || pair.whisper == null) {
            return tool.fallback(pair, selected, "one_available_candidate")
        }
        val request = try { promptBuilder.render(pair) } catch (_: SelectorPromptTooLarge) {
            return tool.fallback(pair, selected, "prompt_exceeds_context_budget")
        } catch (_: Exception) {
            return tool.fallback(pair, selected, "candidate_identity_or_hash_mismatch")
        }
        if (!modelFile.isFile) return tool.fallback(pair, selected, "selector_model_unavailable")
        val prompt = File(dir, "prompt.txt").apply { writeText(request.prompt) }
        val schema = File(dir, "tool-schema.json").apply { writeText(request.schema) }
        val template = File(dir, "nonthinking.jinja").apply { writeText(SelectorPrompt.NonThinkingTemplate) }
        val stdout = File(dir, "selector.stdout")
        val stderr = File(dir, "selector.stderr")
        val response = try {
            executor.run("libvexto_llama_exec.so", listOf(
                "-m", modelFile.absolutePath, "--jinja", "--chat-template-file", template.absolutePath,
                "--reasoning", "off", "-cnv", "--single-turn", "--simple-io", "--no-display-prompt",
                "--no-context-shift", "-c", "1024", "-t", "2", "--threads-batch", "2",
                "-b", "128", "-ub", "64", "-ngl", "0", "--no-repack",
                "--cache-type-k", "f16", "--cache-type-v", "f16", "--seed", "42",
                "--temp", "0", "--repeat-penalty", "1.0", "-n", "32",
                "--json-schema-file", schema.absolutePath, "-f", prompt.absolutePath,
            ), dir, stdout, stderr, timeoutMs)
            if (!stdout.isFile || stdout.length() > 8192) {
                return tool.fallback(pair, selected, "selector_response_missing_or_oversized")
            }
            stdout.readText()
        } catch (_: TimeoutCancellationException) {
            return tool.fallback(pair, selected, "selector_timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return tool.fallback(pair, selected, "selector_runtime_failed")
        }
        return when (val result = tool.execute(response, pair, selected)) {
            is AsrToolResult.Selected -> result.selection
            is AsrToolResult.Rejected -> tool.fallback(pair, selected, result.reason, response)
        }
    }
}
