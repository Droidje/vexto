package com.vexto.engine.pipeline

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.Word
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.AsrToken
import com.vexto.engine.review.ImmutableAsrCandidate
import com.vexto.engine.review.LocalNativeExecutor
import com.vexto.engine.review.LocalNativeResult
import com.vexto.engine.review.sha256
import com.vexto.engine.review.writeImmutableArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** One worker's serial requests, whole candidates. Its executor can retain a native model/context. */
class WhisperComparisonProvider(
    private val executor: LocalNativeExecutor,
    private val modelFile: File,
    private val threads: Int = 4,
    private val beamSize: Int = 1,
    private val bestOf: Int = 1,
    private val flashAttention: Boolean = true,
    private val timeoutMs: Long = 600_000,
    private val audioContext: Int = 768,
    private val language: String = "ru",
    private val fastDecoding: Boolean = false,
) {
    suspend fun generate(chunk: ComparisonChunk, artifactFile: File): ImmutableAsrCandidate =
        generate(chunk, artifactFile) {}

    /** Progress represents completed audio windows, not a timer-based estimate. */
    suspend fun generate(chunk: ComparisonChunk, artifactFile: File,
                         onProgress: (Float) -> Unit): ImmutableAsrCandidate {
        val capacityMs = minOf(10_000L, audioContext * 20L)
        if (chunk.durationMs <= capacityMs) {
            return generateMeasured(chunk, artifactFile).first.also { onProgress(1f) }
        }
        return generateWindowed(chunk, artifactFile, capacityMs, onProgress)
    }

    /** Whisper's encoder context stays bounded; SDK selection can export this entire candidate. */
    private suspend fun generateWindowed(chunk: ComparisonChunk, artifactFile: File, capacityMs: Long,
                                        onProgress: (Float) -> Unit): ImmutableAsrCandidate {
        val info = PcmWave.inspect(chunk.wav)
        require(info.sampleRate == 16000 && info.sampleCount > 0)
        val window = capacityMs * 16
        val work = File(requireNotNull(artifactFile.parentFile), artifactFile.nameWithoutExtension + "-windows")
        val texts = mutableListOf<String>()
        val tokens = mutableListOf<AsrToken>()
        val words = mutableListOf<Word>()
        var from = 0L
        var index = 0
        while (from < info.sampleCount) {
            var to = minOf(from + window, info.sampleCount)
            if (info.sampleCount - to in 1..15) to = info.sampleCount
            val wav = File(work, "w$index.wav")
            wav.parentFile?.mkdirs()
            PcmWave.crop(chunk.wav, wav, from, to)
            val slice = ComparisonChunk("w$index", wav, sha256(wav.readBytes()), (to - from + 15) / 16)
            val piece = generateMeasured(slice, File(work, "w$index.json")).first
            val offset = from * 1000 / 16000
            texts += piece.text
            tokens += piece.tokens.map { it.copy(startMs = it.startMs?.plus(offset), endMs = it.endMs?.plus(offset)) }
            words += piece.words.map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
            // Native JSON and immutable text remain; PCM is reproducible from the source chunk.
            check(wav.delete()) { "Cannot release completed Whisper window" }
            from = to
            index++
            onProgress(from.toFloat() / info.sampleCount)
        }
        return ImmutableAsrCandidate.create(chunk.id, AsrSource.WHISPER, chunk.inputSha256,
            texts.joinToString(""), tokens, words, artifactFile)
    }

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
        require(audioContext in 1..1500) { "invalid_audio_context" }
        // Pinned whisper.cpp: 2 mel frames per encoder frame, HOP160 / 16kHz = 10ms.
        require(chunk.durationMs <= audioContext * 20L) { "chunk_exceeds_audio_context" }
        require(chunk.inputSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid_chunk_hash" }
        require(sha256(chunk.wav.readBytes()) == chunk.inputSha256) { "chunk_input_hash_mismatch" }
        require(modelFile.isFile) { "whisper_model_missing" }
        require(threads in 1..8 && beamSize >= 1 && bestOf >= 1)
        require(language.matches(Regex("[a-z]{2,3}"))) { "invalid_whisper_language" }
        require(!artifactFile.exists()) { "candidate_artifact_already_exists" }
        val dir = requireNotNull(artifactFile.parentFile)
        dir.mkdirs()
        val prefix = File(dir, artifactFile.nameWithoutExtension + ".native")
        val original = File(prefix.absolutePath + ".json")
        require(!original.exists()) { "native_artifact_already_exists" }
        val arguments = mutableListOf("-m", modelFile.absolutePath, "-f", chunk.wav.absolutePath,
            "-of", prefix.absolutePath, "-ojf", "-l", language, "-ng", "-t", threads.toString(),
            "-bs", beamSize.toString(), "-bo", bestOf.toString(), "-ac", audioContext.toString())
        if (flashAttention) arguments += "-fa"
        if (fastDecoding) arguments += "--no-temperature-fallback"
        val result = executor.run("libvexto_whisper_exec.so", arguments, dir,
            File(dir, artifactFile.nameWithoutExtension + ".stdout"),
            File(dir, artifactFile.nameWithoutExtension + ".stderr"), timeoutMs)
        require(sha256(chunk.wav.readBytes()) == chunk.inputSha256) { "chunk_input_hash_mismatch_after_inference" }
        require(original.isFile && original.length() <= 4_000_000) { "whisper_json_missing_or_oversized" }
        val nativeBytes = original.readBytes()
        val nativeHash = sha256(nativeBytes)
        val parsed = parseNativeJson(nativeBytes.toString(Charsets.UTF_8), chunk.durationMs)
        val profile = buildJsonObject {
            put("schema_version", 1); put("chunk_id", chunk.id); put("input_sha256", chunk.inputSha256)
            put("native_json_sha256", nativeHash)
            put("model_file", modelFile.name); put("model_size_bytes", modelFile.length())
            put("audio_context_frames", audioContext); put("encoder_frame_ms", 20)
            put("audio_context_capacity_ms", audioContext * 20L); put("input_duration_ms", chunk.durationMs)
            put("threads", threads); put("beam_size", beamSize); put("best_of", bestOf)
            put("flash_attention", flashAttention); put("use_gpu", false); put("language", language)
            put("fast_decoding", fastDecoding); put("temperature_fallback", !fastDecoding)
            put("elapsed_ms", result.elapsedMs); put("peak_combined_rss_bytes", result.peakCombinedRssBytes)
        }
        writeImmutableArtifact(File(dir, artifactFile.nameWithoutExtension + ".profile-audit.json"),
            profile.toString().toByteArray(Charsets.UTF_8))
        val timing = buildJsonObject {
            put("schema_version", 1); put("chunk_id", chunk.id); put("input_sha256", chunk.inputSha256)
            put("native_json_sha256", nativeHash)
            put("policy", "unavailable_or_invalid_offset_pair_to_null_no_interpolation")
            put("issues", buildJsonArray {
                parsed.timingIssues.forEach { issue -> add(buildJsonObject {
                    put("segment_index", issue.segmentIndex); put("token_index", issue.tokenIndex)
                    put("reason", issue.reason); put("native_from", issue.nativeFrom ?: JsonNull)
                    put("native_to", issue.nativeTo ?: JsonNull)
                }) }
            })
        }
        writeImmutableArtifact(File(dir, artifactFile.nameWithoutExtension + ".timing-audit.json"),
            timing.toString().toByteArray(Charsets.UTF_8))
        return ImmutableAsrCandidate.create(chunk.id, AsrSource.WHISPER, chunk.inputSha256,
            parsed.text, parsed.tokens, parsed.words, artifactFile) to result
    }

    data class TokenTimingIssue(val segmentIndex: Int, val tokenIndex: Int, val reason: String,
        val nativeFrom: JsonElement?, val nativeTo: JsonElement?)
    data class ParsedWhisper(val text: String, val tokens: List<AsrToken>, val words: List<Word>,
        val timingIssues: List<TokenTimingIssue> = emptyList())

    companion object {
        /** Parse the official -ojf file; stdout is never used as an ASR candidate. */
        fun parseNativeJson(raw: String, durationMs: Long): ParsedWhisper {
            val root = Json.parseToJsonElement(raw).jsonObject
            val segments = root.getValue("transcription").jsonArray
            val text = StringBuilder()
            val tokens = mutableListOf<AsrToken>()
            val words = mutableListOf<Word>()
            val timingIssues = mutableListOf<TokenTimingIssue>()
            for ((segmentIndex, item) in segments.withIndex()) {
                val segment = item.jsonObject
                val segmentText = segment.getValue("text").jsonPrimitive.also { require(it.isString) }.content
                text.append(segmentText) // no trim/rewriting: the entire original native text survives
                val segmentTokens = segment["tokens"]?.let { value ->
                    if (value == JsonNull) emptyList() else value.jsonArray.mapIndexed { tokenIndex, tokenValue ->
                        val token = tokenValue.jsonObject
                        val tokenText = token.getValue("text").jsonPrimitive.also { require(it.isString) }.content
                        val offsets = token["offsets"] as? JsonObject
                        val nativeFrom = offsets?.get("from")
                        val nativeTo = offsets?.get("to")
                        fun time(value: JsonElement?): Long? = (value as? JsonPrimitive)
                            ?.takeUnless { it.isString }?.longOrNull
                        val from = time(nativeFrom)
                        val to = time(nativeTo)
                        val reason = when {
                            from == null || to == null -> "unavailable_or_noninteger_offsets"
                            from < 0 || to < 0 -> "negative_offsets"
                            to < from -> "reversed_offsets"
                            else -> null
                        }
                        if (reason != null) timingIssues += TokenTimingIssue(segmentIndex, tokenIndex,
                            reason, nativeFrom, nativeTo)
                        // Native JSON remains unchanged. Invalid pairs cannot become word timing evidence.
                        AsrToken(tokenText, if (reason == null) from else null,
                            if (reason == null) to else null, token["id"]?.jsonPrimitive?.intOrNull)
                    }
                } ?: emptyList()
                tokens += segmentTokens
                val lexical = segmentTokens.filterNot { it.text.startsWith("[_") || it.text.startsWith("<|") }
                if (lexical.joinToString("") { it.text }.trim() == segmentText.trim()) {
                    words += tokenWords(lexical, durationMs)
                }
            }
            return ParsedWhisper(text.toString(), tokens.toList(), words.toList(), timingIssues.toList())
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
