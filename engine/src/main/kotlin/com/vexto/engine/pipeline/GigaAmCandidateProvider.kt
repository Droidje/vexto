package com.vexto.engine.pipeline

import com.k2fsa.sherpa.onnx.*
import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.Word
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.AsrToken
import com.vexto.engine.review.ImmutableAsrCandidate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Exact native E2E RNNT text. No normalization, punctuation repair or spread timestamps. */
class GigaAmCandidateProvider(models: Models, threads: Int = 2) : Closeable {
    data class Models(val encoder: File, val decoder: File, val joiner: File, val tokens: File)
    private val recognizer: OfflineRecognizer
    init {
        require(threads in 1..4)
        require(listOf(models.encoder, models.decoder, models.joiner, models.tokens).all { it.isFile })
        recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = models.encoder.absolutePath,
                    decoder = models.decoder.absolutePath, joiner = models.joiner.absolutePath),
                tokens = models.tokens.absolutePath, numThreads = threads,
                modelType = "nemo_transducer", provider = "cpu"), decodingMethod = "greedy_search"))
    }

    suspend fun generate(chunk: ComparisonChunk, artifactFile: File): ImmutableAsrCandidate {
        require(!artifactFile.exists()) { "Candidate artifact must be fresh" }
        require(fileSha256(chunk.wav).equals(chunk.inputSha256, ignoreCase = true)) { "Chunk input SHA mismatch" }
        val info = PcmWave.inspect(chunk.wav)
        require(info.sampleRate == 16000 && info.sampleCount in 1..480000)
        require(kotlin.math.abs(info.sampleCount * 1000 / 16000 - chunk.durationMs) <= 1)
        currentCoroutineContext().ensureActive()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(PcmWave.readFloat(chunk.wav, 0, info.sampleCount.toInt()), 16000)
            recognizer.decode(stream)
            currentCoroutineContext().ensureActive()
            val result = recognizer.getResult(stream)
            val raw = File(artifactFile.parentFile, artifactFile.nameWithoutExtension + ".native.json")
            require(!raw.exists()) { "Native ASR artifact must be fresh" }
            raw.parentFile?.mkdirs()
            val journal = buildJsonObject {
                put("chunk_id", chunk.id); put("source", "gigaam"); put("input_sha256", chunk.inputSha256)
                put("text", result.text)
                put("tokens", JsonArray(result.tokens.map { JsonPrimitive(it) }))
                put("timestamps", JsonArray(result.timestamps.map(::rawNumber)))
                put("durations", JsonArray(result.durations.map(::rawNumber)))
                put("native_words", JsonArray(result.words.map { JsonPrimitive(it) }))
                put("timestamp_unit", "seconds_relative_to_chunk")
                put("last_token_tail_ms_estimate", 40)
            }
            raw.writeText(journal.toString())
            val aligned = GigaTokenAlignment.align(result.text, result.tokens, result.timestamps, chunk.durationMs)
            return ImmutableAsrCandidate.create(chunk.id, AsrSource.GIGAAM, chunk.inputSha256,
                result.text, aligned.first, aligned.second, artifactFile)
        } finally {
            stream.release()
        }
    }
    override fun close() = recognizer.release()
    private fun rawNumber(value: Float): JsonPrimitive = if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())

    private fun fileSha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) {
            val n = input.read(b); if (n < 0) break; sha.update(b, 0, n)
        } }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}

internal object GigaTokenAlignment {
    fun align(text: String, pieces: Array<String>, timestamps: FloatArray, durationMs: Long): Pair<List<AsrToken>, List<Word>> {
        require(durationMs > 0)
        if (timestamps.isEmpty()) return pieces.map { AsrToken(it) } to emptyList()
        require(pieces.size == timestamps.size) { "Native token/timestamp count differs" }
        require(timestamps.all { it.isFinite() && it >= 0 && it * 1000 <= durationMs }) { "Invalid native timestamp" }
        require(timestamps.asSequence().zipWithNext().all { (a,b) -> b >= a }) { "Nonmonotonic native timestamps" }
        val literal = pieces.joinToString("")
        // Keep native token bytes; SentencePiece boundary markers only affect derived word surfaces.
        val surfaces = if (literal.trim() == text.trim()) pieces.toList() else pieces.map { it.replace('▁',' ') }
        val joined = surfaces.joinToString("")
        require(joined.trim() == text.trim()) { "Native tokens do not reconstruct exact ASR text" }
        val tokens = pieces.mapIndexed { i, piece ->
            val start = (timestamps[i] * 1000).toLong()
            val end = if (i+1 < pieces.size) (timestamps[i+1] * 1000).toLong() else minOf(durationMs, start+40)
            AsrToken(piece, start, end)
        }
        val offsets = IntArray(pieces.size+1)
        surfaces.forEachIndexed { i, piece -> offsets[i+1] = offsets[i]+piece.length }
        val words = Regex("(?U)\\S+").findAll(joined).map { match ->
            val first = pieces.indices.first { offsets[it+1] > match.range.first }
            val last = pieces.indices.last { offsets[it] <= match.range.last && offsets[it+1] > offsets[it] }
            Word(match.value, tokens[first].startMs!!, tokens[last].endMs!!)
        }.toList()
        return tokens to words
    }
}
