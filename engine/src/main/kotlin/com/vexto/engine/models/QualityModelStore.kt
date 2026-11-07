package com.vexto.engine.models

import android.content.Context
import com.vexto.engine.quality.JobFiles
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

@Serializable
data class QualityArtifact(val path: String, val sha256: String, val bytes: Long, val url: String = "", val license: String = "")

@Serializable
data class QualityManifest(val version: String, val artifacts: Map<String, QualityArtifact>) {
    fun validate() {
        require(version.matches(Regex("[A-Za-z0-9._-]+")))
        require(artifacts.keys.containsAll(Required)) { "Incomplete quality model pack" }
        artifacts.values.forEach { require(it.bytes > 0 && it.sha256.matches(Regex("[0-9a-f]{64}"))) }
    }
    companion object {
        val Required = setOf("gtcrn", "nemotron", "giga_encoder", "giga_decoder", "giga_joiner", "giga_tokens", "whisper", "qwen", "emotion", "voiceprint_embedding")
    }
}

class QualityModels(val dir: File, val manifest: QualityManifest, val verificationReused: Boolean = false) {
    fun file(id: String): File = JobFiles.inside(dir, manifest.artifacts.getValue(id).path)
}

class OfflineModelPackException(message: String, cause: IOException? = null) : IOException(message, cause)

/** Offline packs live in app-scoped storage. No multi-GB duplicate copy inside APK/filesDir. */
object QualityModelStore {
    private val verificationCache = ModelVerificationCache()

    private fun readManifest(context: Context): Pair<QualityManifest, String> {
        val bytes = context.assets.open("quality/manifest.json").use { it.readBytes() }
        val manifest = JobFiles.json.decodeFromString<QualityManifest>(bytes.toString(Charsets.UTF_8))
            .also(QualityManifest::validate)
        val digest = ModelVerificationCache.manifestDigest(bytes)
        return manifest to digest
    }
    fun manifest(context: Context): QualityManifest = readManifest(context).first
    fun root(context: Context, manifest: QualityManifest): File = File(
        context.getExternalFilesDir(null) ?: context.filesDir, "quality-models/${manifest.version}")

    suspend fun prepare(context: Context, progress: (Float) -> Unit): QualityModels {
        val (manifest, manifestDigest) = readManifest(context)
        val root = root(context, manifest)
        val entries = manifest.artifacts.entries.toList()
        val files = entries.map { (_, artifact) ->
            JobFiles.inside(root, artifact.path) // Validate containment, retaining the path for NOFOLLOW below.
            File(root, artifact.path)
        }
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val hit = try {
            verificationCache.verify(manifestDigest, files) {
                entries.forEachIndexed { i, (id, artifact) ->
                    coroutine.ensureActive()
                    val file = files[i]
                    if (!file.isFile || file.length() != artifact.bytes || JobFiles.sha256(file) != artifact.sha256) {
                        throw OfflineModelPackException("Не установлен полный набор моделей: $id. Загрузите проверенный офлайн-пакет ${manifest.version} в ${root.name}.")
                    }
                    progress((i + 1f) / entries.size)
                }
            }
        } catch (error: OfflineModelPackException) {
            throw error
        } catch (error: IOException) {
            throw OfflineModelPackException("Не удалось проверить локальный комплект моделей.", error)
        }
        coroutine.ensureActive()
        if (hit) progress(1f)
        return QualityModels(root, manifest, verificationReused = hit)
    }
}
