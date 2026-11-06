package com.vexto.engine.pipeline

import com.vexto.engine.model.Word
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.AsrToken
import com.vexto.engine.review.ImmutableAsrCandidate
import com.vexto.engine.review.LocalNativeExecutor
import com.vexto.engine.review.LocalNativeResult
import com.vexto.engine.review.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/** Same immutable WAV and SHA for both ASR engines; offsets are relative to this chunk. */
data class ComparisonChunk(val id: String, val wav: File, val inputSha256: String, val durationMs: Long)

data class WhisperComparisonAttempt(val chunkId: String, val candidate: ImmutableAsrCandidate?,
    val failureCode: String? = null, val nativeResult: LocalNativeResult? = null)

/** Serial native children, whole candidates. Root adapts LocalNativeExecutor to NativeProcessRunner. */
class WhisperComparisonProvider(
    private val executor: LocalNativeExecutor,
    private val modelFile: File,
    private val threads: Int = 4,
    private val beamSize: Int = 1,
    private val bestOf: Int = 1,
    private val flashAttention: Boolean = true,
    private val timeoutMs: Long = 600_000,
) {
    suspend fun generate(chunk: ComparisonChunk, artifactFile: File): ImmutableAsrCandidate =
        generateMeasured(chunk, artifactFile).first

    suspend fun generateAll(chunks: List<ComparisonChunk>, workDir: File): List<WhisperComparisonAttempt> {
        require(chunks.map { it.id }.distinct().size == chunks.size) { "duplicate_chunk_id" }
        return chunks.map { chunk ->
            try {
                val (candidate, result) = generateMeasured(chunk, File(File(workDir, chunk.id), "whisper.json"))
                WhisperComparisonAttempt(chunk.id, candidate, nativeResult = result)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { WhisperComparisonAttempt(chunk.id, null, "whisper_candidate_failed") }
        }
    }

    private suspend fun generateMeasured(chunk: ComparisonChunk, artifactFile: File): Pair<ImmutableAsrCandidate, LocalNativeResult> {
        require(chunk.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))) { "invalid_chunk_id" }
        require(chunk.durationMs in 1L..15_000L) { "comparison_chunk_too_long" }
        require(chunk.inputSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid_chunk_hash" }
        require(sha256(chunk.wav.readBytes()) == chunk.inputSha256) { "chunk_input_hash_mismatch" }
        require(modelFile.isFile) { "whisper_model_missing" }
        require(threads in 1..8 && beamSize >= 1 && bestOf >= 1)
        require(!artifactFile.exists()) { "candidate_artifact_already_exists" }
        val dir = requireNotNull(artifactFile.parentFile)
        dir.mkdirs()
        val prefix = File(dir, artifactFile.nameWithoutExtension + ".native")
        val original = File(prefix.absolutePath + ".json")
        require(!original.exists()) { "native_artifact_already_exists" }
        val arguments = mutableListOf("-m", modelFile.absolutePath, "-f", chunk.wav.absolutePath,
            "-of", prefix.absolutePath, "-ojf", "-l", "ru", "-ng", "-t", threads.toString(),
            "-bs", beamSize.toString(), "-bo", bestOf.toString())
        if (flashAttention) arguments += "-fa"
        val result = executor.run("libvexto_whisper_exec.so", arguments, dir,
            File(dir, artifactFile.nameWithoutExtension + ".stdout"),
            File(dir, artifactFile.nameWithoutExtension + ".stderr"), timeoutMs)
        require(sha256(chunk.wav.readBytes()) == chunk.inputSha256) { "chunk_input_hash_mismatch_after_inference" }
        require(original.isFile && original.length() <= 4_000_000) { "whisper_json_missing_or_oversized" }
        val parsed = parseNativeJson(original.readText(), chunk.durationMs)
        return ImmutableAsrCandidate.create(chunk.id, AsrSource.WHISPER, chunk.inputSha256,
            parsed.text, parsed.tokens, parsed.words, artifactFile) to result
    }

    data class ParsedWhisper(val text: String, val tokens: List<AsrToken>, val words: List<Word>)

    companion object {
        /** Parse the official -ojf file; stdout is never used as an ASR candidate. */
        fun parseNativeJson(raw: String, durationMs: Long): ParsedWhisper {
            val root = Json.parseToJsonElement(raw).jsonObject
            val segments = root.getValue("transcription").jsonArray
            val text = StringBuilder()
            val tokens = mutableListOf<AsrToken>()
            val words = mutableListOf<Word>()
            for (item in segments) {
                val segment = item.jsonObject
                val segmentText = segment.getValue("text").jsonPrimitive.also { require(it.isString) }.content
                text.append(segmentText) // no trim/rewriting: the entire original native text survives
                val segmentTokens = segment["tokens"]?.let { value ->
                    if (value == JsonNull) emptyList() else value.jsonArray.map { tokenValue ->
                        val token = tokenValue.jsonObject
                        val tokenText = token.getValue("text").jsonPrimitive.also { require(it.isString) }.content
                        val offsets = token["offsets"]?.jsonObject
                        fun time(key: String): Long? = offsets?.get(key)?.jsonPrimitive?.longOrNull
                        AsrToken(tokenText, time("from"), time("to"), token["id"]?.jsonPrimitive?.intOrNull)
                    }
                } ?: emptyList()
                tokens += segmentTokens
                val lexical = segmentTokens.filterNot { it.text.startsWith("[_") || it.text.startsWith("<|") }
                if (lexical.joinToString("") { it.text }.trim() == segmentText.trim()) {
                    words += tokenWords(lexical, durationMs)
                }
            }
            return ParsedWhisper(text.toString(), tokens.toList(), words.toList())
        }

        private fun tokenWords(tokens: List<AsrToken>, durationMs: Long): List<Word> {
            val result = mutableListOf<Word>()
            var buffer = StringBuilder()
            var start: Long? = null
            var end: Long? = null
            var completeTiming = true
            fun flush() {
                if (buffer.isNotEmpty() && completeTiming && start != null && end != null && end!! >= start!!) {
                    result += Word(buffer.toString(), start!!.coerceIn(0, durationMs), end!!.coerceIn(0, durationMs))
                }
                buffer = StringBuilder(); start = null; end = null; completeTiming = true
            }
            tokens.forEach { token ->
                Regex("\\s+|\\S+").findAll(token.text).forEach { part ->
                    if (part.value.first().isWhitespace()) flush() else {
                        buffer.append(part.value)
                        if (token.startMs == null || token.endMs == null) completeTiming = false
                        token.startMs?.let { start = start?.coerceAtMost(it) ?: it }
                        token.endMs?.let { end = end?.coerceAtLeast(it) ?: it }
                    }
                }
            }
            flush()
            return result
        }
    }
}
