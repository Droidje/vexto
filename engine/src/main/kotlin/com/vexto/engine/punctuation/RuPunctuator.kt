package com.vexto.engine.punctuation

import android.content.Context
import com.vexto.engine.TranscriptionEngine
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Опциональный корректор реплик: RUPunct_small INT8.
 * Исходная строка возвращается без изменений, если модель недоступна вызывающему коду
 * или если метки изменили бы буквы и цифры.
 */
class RuPunctuator private constructor(
    private val ort: RuPunctOrt,
    private val pieces: WordPiece,
) : AutoCloseable {

    fun restore(text: String): String = synchronized(this) {
        RuPunctText.restore(text, pieces) { ids -> ort.labels(ids) }
    }

    private fun restoreForBatch(text: String): String = synchronized(this) {
        RuPunctText.restore(text, pieces) { ids ->
            if (shouldYield()) throw YieldPunctuation()
            ort.labels(ids)
        }
    }

    private class YieldPunctuation : RuntimeException()

    override fun close() = synchronized(this) { ort.close() }

    companion object {
        private const val ModelSha = "b105da023474d98aa13ba18953ae67b04b17bd0595034bc06030c17536893933"
        private const val TokenizerSha = "7ca617388c2092a3a84272025c52bbf3c6db0aee225c0351186295c0b5d3ddc6"
        private val JsonFormat = Json { ignoreUnknownKeys = true }

        /** Returns null when primary model work takes priority. */
        @JvmStatic
        fun tryRestore(context: Context, text: String): String? =
            tryRestoreBatch(context, listOf(text))?.firstOrNull()

        /** One bounded batch shares verification, tokenizer and ORT session initialization. */
        @JvmStatic
        fun tryRestoreBatch(context: Context, texts: List<String>): List<String>? {
            if (texts.isEmpty()) return emptyList()
            val gate = TranscriptionEngine.lock
            if (!gate.tryLock()) return null
            try {
                if (shouldYield()) return null
                return load(context).use { model ->
                    val result = ArrayList<String>(minOf(texts.size, BatchSize))
                    for (text in texts.take(BatchSize)) {
                        if (shouldYield()) break
                        try {
                            result += model.restoreForBatch(text)
                        } catch (_: YieldPunctuation) {
                            // Retain completed lines only. The interrupted line is retried later.
                            break
                        }
                    }
                    result
                }
            } finally {
                gate.unlock()
            }
        }

        private const val BatchSize = 16

        private fun shouldYield(): Boolean =
            Thread.currentThread().isInterrupted || TranscriptionEngine.isTranscriptionPending

        @JvmStatic
        fun load(context: Context): RuPunctuator {
            val dir = File(context.filesDir, "punctuation")
            check(dir.mkdirs() || dir.isDirectory)
            val model = File(dir, "rupunct_small_int8.onnx")
            val tokenizer = File(dir, "tokenizer.json")
            synchronized(ExtractionLock) {
                prepare(context, "models/punctuation/rupunct_small_int8.onnx", model, ModelSha)
                prepare(context, "models/punctuation/tokenizer.json", tokenizer, TokenizerSha)
            }
            val spec = JsonFormat.decodeFromString(TokenizerFile.serializer(), tokenizer.readText()).model
            val pieces = WordPiece(spec.vocab, spec.continuing_subword_prefix, spec.max_input_chars_per_word)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
            return RuPunctuator(RuPunctOrt(model.absolutePath, threads), pieces)
        }

        private val ExtractionLock = Any()

        private fun verified(file: File, expected: String): Boolean {
            if (!file.isFile) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val block = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(block)
                    if (count < 0) break
                    digest.update(block, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == expected
        }

        private fun prepare(context: Context, asset: String, target: File, expected: String) {
            if (verified(target, expected)) return
            val temporary = File.createTempFile(target.name, ".pending", target.parentFile)
            try {
                context.assets.open(asset).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(verified(temporary, expected)) { "Invalid bundled punctuation model" }
                Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
    }
}

@Serializable
private data class TokenizerFile(val model: WordPieceSpec)

@Serializable
private data class WordPieceSpec(
    val vocab: Map<String, Int>,
    val continuing_subword_prefix: String = "##",
    val max_input_chars_per_word: Int = 100,
)
