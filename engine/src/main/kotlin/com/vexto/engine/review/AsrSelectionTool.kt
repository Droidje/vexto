package com.vexto.engine.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File

sealed interface AsrToolResult {
    data class Selected(val selection: AsrSelection) : AsrToolResult
    data class Rejected(val reason: String) : AsrToolResult
}

/** Local tool boundary. No model-generated text is ever turned into ASR text. */
class AsrSelectionTool {
    val name: String = "select_asr_variant"

    fun execute(arguments: String, pair: AsrCandidatePair, selectedFile: File): AsrToolResult {
        val fields = try { FlatStringObject.parse(arguments) } catch (_: IllegalArgumentException) {
            return AsrToolResult.Rejected("invalid_tool_json")
        }
        if (fields.keys != setOf("tool", "chunk_id", "source") || fields["tool"] != name ||
            fields["chunk_id"] != pair.chunkId) return AsrToolResult.Rejected("invalid_tool_arguments")
        val source = AsrSource.entries.firstOrNull { it.wireName == fields["source"] }
            ?: return AsrToolResult.Rejected("invalid_tool_source")
        if (!allPresentCandidatesIntact(pair)) return AsrToolResult.Rejected("candidate_identity_or_hash_mismatch")
        val candidate = when (source) { AsrSource.GIGAAM -> pair.gigaam; AsrSource.WHISPER -> pair.whisper }
            ?: return AsrToolResult.Rejected("selected_candidate_missing")
        return AsrToolResult.Selected(copyCandidate(candidate, selectedFile, AsrSelectionStatus.SELECTED_BY_LLM,
            "tool", "valid_local_tool_call", sha256(arguments.toByteArray())))
    }

    /** Equal quality priority. With two candidates, GigaAM is only an unconfirmed display policy. */
    fun fallback(pair: AsrCandidatePair, selectedFile: File, reason: String, response: String? = null): AsrSelection {
        val candidates = listOfNotNull(intact(pair.gigaam, pair, AsrSource.GIGAAM),
            intact(pair.whisper, pair, AsrSource.WHISPER))
        val candidate = candidates.firstOrNull()
            ?: error("no_intact_asr_candidate")
        val status = if (candidates.size == 1) AsrSelectionStatus.ONE_AVAILABLE else AsrSelectionStatus.UNRESOLVED
        return copyCandidate(candidate, selectedFile, status, "unconfirmed_display_fallback", reason,
            response?.let { sha256(it.toByteArray()) })
    }

    private fun intact(candidate: ImmutableAsrCandidate?, pair: AsrCandidatePair, source: AsrSource): ImmutableAsrCandidate? {
        if (candidate == null) return null
        return try { candidate.verifyIdentity(pair.chunkId, pair.inputSha256, source); candidate }
        catch (_: Exception) { null }
    }

    private fun allPresentCandidatesIntact(pair: AsrCandidatePair): Boolean =
        (pair.gigaam == null || intact(pair.gigaam, pair, AsrSource.GIGAAM) != null) &&
            (pair.whisper == null || intact(pair.whisper, pair, AsrSource.WHISPER) != null)

    private fun copyCandidate(candidate: ImmutableAsrCandidate, file: File, status: AsrSelectionStatus, decision: String,
        reason: String, responseHash: String?): AsrSelection {
        require(!file.exists()) { "selected_artifact_already_exists" }
        val original = candidate.verifiedBytes()
        writeImmutableArtifact(file, original)
        val selectedHash = sha256(file.readBytes())
        check(selectedHash == candidate.artifactSha256) { "selected_artifact_copy_mismatch" }
        candidate.verifiedBytes()
        return AsrSelection(status, candidate, file, SelectionAudit(candidate.chunkId, candidate.source, decision,
            reason, candidate.inputSha256, candidate.artifactSha256, selectedHash, responseHash,
            selectedByLlm = status == AsrSelectionStatus.SELECTED_BY_LLM))
    }
}

/** Exactly one flat object of JSON strings; duplicate keys and trailing text are rejected. */
internal object FlatStringObject {
    fun parse(raw: String): Map<String, String> {
        var text = raw.trim()
        if (text.endsWith("[end of text]")) text = text.removeSuffix("[end of text]").trimEnd()
        var index = 0
        fun space() { while (index < text.length && text[index] in " \t\n\r") index++ }
        fun expect(character: Char) { space(); require(index < text.length && text[index++] == character) }
        fun string(): String {
            space(); val start = index
            require(index < text.length && text[index++] == '"')
            var escaped = false
            while (index < text.length) {
                val character = text[index++]
                if (!escaped && character == '"') {
                    return Json.decodeFromString<String>(text.substring(start, index))
                }
                if (!escaped && character == '\\') escaped = true else escaped = false
            }
            throw IllegalArgumentException("unterminated_json_string")
        }
        expect('{')
        val result = linkedMapOf<String, String>()
        space()
        if (index < text.length && text[index] == '}') index++ else {
            while (true) {
                val key = string(); expect(':'); val value = string()
                require(key !in result); result[key] = value
                space(); require(index < text.length)
                if (text[index] == '}') { index++; break }
                expect(',')
            }
        }
        space(); require(index == text.length)
        return result
    }
}
