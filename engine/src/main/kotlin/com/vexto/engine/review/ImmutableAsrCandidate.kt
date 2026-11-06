package com.vexto.engine.review

import com.vexto.engine.model.Word
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections

@Serializable
enum class AsrSource(val wireName: String) {
    @SerialName("gigaam") GIGAAM("gigaam"), @SerialName("whisper") WHISPER("whisper")
}

/** Original chunk-relative token offsets; null means the ASR supplied no offset. */
data class AsrToken(val text: String, val startMs: Long? = null, val endMs: Long? = null, val id: Int? = null)
@Serializable
enum class AsrSelectionStatus { SELECTED_BY_LLM, ONE_AVAILABLE, UNRESOLVED }

/** A defensive snapshot. Its on-disk artifact must still match the recorded hash. */
class ImmutableAsrCandidate private constructor(
    val chunkId: String,
    val source: AsrSource,
    val inputSha256: String,
    val text: String,
    tokens: List<AsrToken>,
    words: List<Word>,
    val artifactFile: File,
    val artifactSha256: String,
) {
    val tokens: List<AsrToken> = Collections.unmodifiableList(ArrayList(tokens))
    val words: List<Word> = Collections.unmodifiableList(ArrayList(words))

    fun verifiedBytes(): ByteArray = artifactFile.readBytes().also {
        check(sha256(it) == artifactSha256) { "candidate_artifact_hash_mismatch" }
    }

    fun verifyIdentity(chunkId: String, inputSha256: String, source: AsrSource) {
        check(this.chunkId == chunkId && this.inputSha256 == inputSha256 && this.source == source) {
            "candidate_identity_mismatch"
        }
        verifiedBytes()
    }

    companion object {
        /** expectedSha256 comes from the trusted job journal, not from this file. */
        fun load(artifactFile: File, expectedSha256: String): ImmutableAsrCandidate {
            require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid_candidate_hash" }
            val bytes = artifactFile.readBytes()
            check(sha256(bytes) == expectedSha256) { "candidate_artifact_hash_mismatch" }
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(value.keys == setOf("schema_version", "chunk_id", "source", "input_sha256", "text", "tokens", "words"))
            require(value.getValue("schema_version").jsonPrimitive.intOrNull == 1)
            fun string(key: String): String = value.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
            val chunkId = string("chunk_id")
            val inputHash = string("input_sha256")
            val source = AsrSource.entries.single { it.wireName == string("source") }
            val text = string("text")
            val tokens = value.getValue("tokens").jsonArray.map { item ->
                val token = item.jsonObject
                require(token.keys == setOf("text", "start_ms", "end_ms", "id"))
                val tokenText = token.getValue("text").jsonPrimitive.also { require(it.isString) }.content
                fun time(key: String): Long? = token.getValue(key).let {
                    if (it == JsonNull) null else requireNotNull(it.jsonPrimitive.longOrNull)
                }
                val id = token.getValue("id").let { if (it == JsonNull) null else requireNotNull(it.jsonPrimitive.intOrNull) }
                AsrToken(tokenText, time("start_ms"), time("end_ms"), id)
            }
            val words = value.getValue("words").jsonArray.map { item ->
                val word = item.jsonObject
                require(word.keys == setOf("text", "start_ms", "end_ms"))
                val wordText = word.getValue("text").jsonPrimitive.also { require(it.isString) }.content
                Word(wordText, requireNotNull(word.getValue("start_ms").jsonPrimitive.longOrNull),
                    requireNotNull(word.getValue("end_ms").jsonPrimitive.longOrNull))
            }
            validate(chunkId, inputHash, tokens, words)
            return ImmutableAsrCandidate(chunkId, source, inputHash, text, tokens, words, artifactFile, expectedSha256)
        }

        private fun validate(chunkId: String, inputSha256: String, tokens: List<AsrToken>, words: List<Word>) {
            require(chunkId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))) { "invalid_chunk_id" }
            require(inputSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid_input_hash" }
            tokens.forEach {
                require(it.startMs == null || it.startMs >= 0) { "invalid_token_time" }
                require(it.endMs == null || (it.startMs != null && it.endMs >= it.startMs)) { "invalid_token_time" }
            }
            words.forEach { require(it.startMs >= 0 && it.endMs >= it.startMs) { "invalid_word_time" } }
        }

        fun create(
            chunkId: String,
            source: AsrSource,
            inputSha256: String,
            text: String,
            tokens: List<AsrToken>,
            words: List<Word>,
            artifactFile: File,
        ): ImmutableAsrCandidate {
            validate(chunkId, inputSha256, tokens, words)
            val snapshotTokens = tokens.toList()
            val snapshotWords = words.toList()
            val bytes = buildJsonObject {
                put("schema_version", 1)
                put("chunk_id", chunkId)
                put("source", source.wireName)
                put("input_sha256", inputSha256)
                put("text", text)
                put("tokens", buildJsonArray {
                    snapshotTokens.forEach { token -> add(buildJsonObject {
                        put("text", token.text)
                        put("start_ms", token.startMs?.let(::JsonPrimitive) ?: JsonNull)
                        put("end_ms", token.endMs?.let(::JsonPrimitive) ?: JsonNull)
                        put("id", token.id?.let(::JsonPrimitive) ?: JsonNull)
                    }) }
                })
                put("words", buildJsonArray {
                    snapshotWords.forEach { word -> add(buildJsonObject {
                        put("text", word.text); put("start_ms", word.startMs); put("end_ms", word.endMs)
                    }) }
                })
            }.toString().toByteArray(Charsets.UTF_8)
            writeImmutableArtifact(artifactFile, bytes)
            val candidate = ImmutableAsrCandidate(chunkId, source, inputSha256, text, snapshotTokens,
                snapshotWords, artifactFile, sha256(bytes))
            candidate.verifiedBytes()
            return candidate
        }
    }
}

data class AsrCandidatePair(
    val chunkId: String,
    val inputSha256: String,
    val gigaam: ImmutableAsrCandidate?,
    val whisper: ImmutableAsrCandidate?,
)

@Serializable
data class SelectionAudit(
    val chunkId: String,
    val source: AsrSource,
    val decision: String,
    val reason: String,
    val inputSha256: String,
    val candidateSha256: String,
    val selectedSha256: String,
    val toolResponseSha256: String? = null,
    val selectedByLlm: Boolean = false,
    val qualityValidated: Boolean = false,
)

/** selectedArtifact is the display copy even if status is UNRESOLVED; it is not a quality verdict. */
data class AsrSelection(val status: AsrSelectionStatus, val displayCandidate: ImmutableAsrCandidate,
    val selectedArtifact: File, val audit: SelectionAudit)

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Publish only complete fsynced artifacts; never overwrite an original or a previous decision. */
internal fun writeImmutableArtifact(file: File, bytes: ByteArray) {
    require(!file.exists()) { "immutable_artifact_already_exists" }
    val destination = file.absoluteFile
    val parent = requireNotNull(destination.parentFile)
    check(parent.mkdirs() || parent.isDirectory)
    val temporary = File.createTempFile(".asr-artifact-", ".pending", parent)
    try {
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        require(!destination.exists()) { "immutable_artifact_already_exists" }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally { temporary.delete() }
}

