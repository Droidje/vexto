package com.vexto.engine.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.nio.ByteBuffer

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
        // Requests are serial; the stage-owned executor retains and ultimately closes its model/context.
        return pairs.map { pair -> selectOne(pair, File(workDir, pair.chunkId)) }
    }

    private suspend fun selectOne(pair: AsrCandidatePair, dir: File): AsrSelection {
        dir.mkdirs()
        val selected = File(dir, "display-candidate.json")
        val boundCall = File(dir, "bound-tool-call.json")
        fun fallback(reason: String, response: String? = null): AsrSelection = tool.fallback(
            pair, selected, reason, response, responseKind = ScopedSourceResponseKind,
            scopedToolCallSha256 = boundCall.takeIf { it.isFile }?.let { sha256(it.readBytes()) })
        if (pair.gigaam == null || pair.whisper == null) {
            return fallback("one_available_candidate")
        }
        if (pair.gigaam.text == pair.whisper.text) return tool.equivalent(pair, selected)
        val request = try { promptBuilder.renderScopedSource(pair) } catch (_: SelectorPromptTooLarge) {
            return fallback("prompt_exceeds_context_budget")
        } catch (_: Exception) {
            return fallback("candidate_identity_or_hash_mismatch")
        }
        if (!modelFile.isFile) return fallback("selector_model_unavailable")
        val args = BoundedQwenRequest.arguments(modelFile, request, dir)
        val stdout = File(dir, "selector.stdout")
        val stderr = File(dir, "selector.stderr")
        val response = try {
            executor.run("libvexto_llama_exec.so", args, dir, stdout, stderr, timeoutMs)
            if (!stdout.isFile || stdout.length() > 8192) {
                return fallback("selector_response_missing_or_oversized")
            }
            // Strict UTF-8 keeps the audit hash equal to the actual stdout bytes.
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(stdout.readBytes())).toString()
        } catch (_: TimeoutCancellationException) {
            return fallback("selector_timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return fallback("selector_runtime_failed")
        }
        return when (val result = tool.executeScopedSource(response, pair, selected, boundCall)) {
            is AsrToolResult.Selected -> result.selection
            is AsrToolResult.Rejected -> fallback(result.reason, response)
        }
    }
}
