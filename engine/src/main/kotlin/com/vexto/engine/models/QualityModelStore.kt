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
        val Required = setOf("gtcrn", "nemotron", "giga_encoder", "giga_decoder", "giga_joiner", "giga_tokens", "whisper", "qwen", "emotion", "voiceprint_embedding", "lid_encoder", "lid_decoder")
        val FastRequired = Required - setOf("giga_encoder", "giga_decoder", "giga_joiner", "giga_tokens", "whisper",
            "gtcrn", "emotion") +
            setOf("giga_fast_model", "giga_fast_tokens", "whisper_fast")
    }
}

class QualityModels(val dir: File, val manifest: QualityManifest, val verificationReused: Boolean = false) {
    fun file(id: String): File = JobFiles.inside(dir, manifest.artifacts.getValue(id).path)
}

class OfflineModelPackException(message: String, cause: IOException? = null) : IOException(message, cause)

/** Offline packs are resolved consistently in the application and each inference worker. */
object QualityModelStore {
    private val verificationCache = ModelVerificationCache()
    private val summaryVerificationCache = ModelVerificationCache()
    private val emotionVerificationCache = ModelVerificationCache()

    private fun readManifest(context: Context): Pair<QualityManifest, String> {
        val bytes = context.assets.open("quality/manifest.json").use { it.readBytes() }
        val manifest = JobFiles.json.decodeFromString<QualityManifest>(bytes.toString(Charsets.UTF_8))
            .also(QualityManifest::validate)
        val digest = ModelVerificationCache.manifestDigest(bytes)
        return manifest to digest
    }
    fun manifest(context: Context): QualityManifest = readManifest(context).first
    fun root(context: Context, manifest: QualityManifest): File {
        val storage = if (BundledModelPack.installed(context)) context.noBackupFilesDir
            else context.getExternalFilesDir(null) ?: context.filesDir
        return File(storage, "quality-models/${manifest.version}")
    }

    suspend fun prepare(context: Context, progress: (Float) -> Unit): QualityModels = prepare(context, false, progress)

    /** Both profiles ship in the APK; extract and verify only the profile this job will use. */
    suspend fun prepare(context: Context, fast: Boolean, progress: (Float) -> Unit): QualityModels =
        prepare(context, if (fast) QualityManifest.FastRequired else QualityManifest.Required, progress = progress)

    /** Determine the recording language before extracting or hashing either Whisper model. */
    suspend fun preparePipeline(context: Context, fast: Boolean, progress: (Float) -> Unit): QualityModels =
        prepare(context, (if (fast) QualityManifest.FastRequired else QualityManifest.Required) -
            setOf("whisper", "whisper_fast"), progress = progress)

    /** Called only for routes that use Whisper; all weights remain bundled in the offline APK. */
    suspend fun prepareWhisper(context: Context, fast: Boolean, progress: (Float) -> Unit): QualityModels =
        prepare(context, setOf(if (fast) "whisper_fast" else "whisper"), progress = progress)

    suspend fun prepareRefinement(context: Context, slavic: Boolean, progress: (Float) -> Unit): QualityModels =
        prepare(context, setOf("whisper") + if (slavic)
            setOf("giga_encoder", "giga_decoder", "giga_joiner", "giga_tokens") else emptySet(), progress = progress)

    internal suspend fun prepareSummary(context: Context): QualityModels =
        prepare(context, setOf("qwen"), summaryVerificationCache) {}

    /** Fast jobs publish their dialogue before this deferred extraction/verification. */
    internal suspend fun prepareEmotion(context: Context): QualityModels =
        prepare(context, setOf("emotion"), emotionVerificationCache) {}

    private suspend fun prepare(
        context: Context,
        required: Set<String>,
        cache: ModelVerificationCache = verificationCache,
        progress: (Float) -> Unit,
    ): QualityModels {
        val (manifest, manifestDigest) = readManifest(context)
        val root = root(context, manifest)
        if (!manifest.artifacts.keys.containsAll(required)) {
            throw OfflineModelPackException("В установленном APK отсутствуют модели выбранного режима. Установите полный офлайн-комплект.")
        }
        val entries = manifest.artifacts.entries.filter { it.key in required }
        val files = entries.map { (_, artifact) ->
            JobFiles.inside(root, artifact.path) // Validate containment, retaining the path for NOFOLLOW below.
            File(root, artifact.path)
        }
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        if (BundledModelPack.installed(context)) {
            val hit = BundledModelPack.prepare(
                context, manifestDigest, entries.map { it.value }, files, cache, progress,
            )
            return QualityModels(root, manifest, verificationReused = hit)
        }
        val hit = try {
            cache.verify(manifestDigest, files) {
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
