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

class QualityModels(val dir: File, val manifest: QualityManifest) {
    fun file(id: String): File = JobFiles.inside(dir, manifest.artifacts.getValue(id).path)
}

/** Offline packs live in app-scoped storage. No multi-GB duplicate copy inside APK/filesDir. */
object QualityModelStore {
    fun manifest(context: Context): QualityManifest = context.assets.open("quality/manifest.json").bufferedReader().use {
        JobFiles.json.decodeFromString<QualityManifest>(it.readText()).also(QualityManifest::validate)
    }
    fun root(context: Context, manifest: QualityManifest): File = File(
        context.getExternalFilesDir(null) ?: context.filesDir, "quality-models/${manifest.version}")

    suspend fun prepare(context: Context, progress: (Float) -> Unit): QualityModels {
        val manifest = manifest(context)
        val root = root(context, manifest)
        manifest.artifacts.entries.forEachIndexed { i, (id, artifact) ->
            currentCoroutineContext().ensureActive()
            val file = JobFiles.inside(root, artifact.path)
            if (!file.isFile || file.length() != artifact.bytes || JobFiles.sha256(file) != artifact.sha256) {
                throw IOException("Не установлен полный набор моделей: $id. Загрузите проверенный офлайн-пакет ${manifest.version} в ${root.name}.")
            }
            progress((i + 1f) / manifest.artifacts.size)
        }
        return QualityModels(root, manifest)
    }
}
