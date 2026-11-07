package com.vexto.engine.models

import android.content.Context
import com.vexto.engine.quality.JobFiles
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Fixed operating point selected on calibration data, never tuned on Android test clips. */
@Serializable
data class OverlapManifest(
    val version: String,
    val path: String,
    val sha256: String,
    val bytes: Long,
    val threshold: Double,
    @SerialName("window_samples") val windowSamples: Int,
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("encoder_sha256") val encoderSha256: String? = null,
    @SerialName("head_sha256") val headSha256: String? = null,
) {
    fun validate() {
        require(version.matches(Regex("[A-Za-z0-9._-]+")))
        require(path == "overlap/wavlm.onnx" && sha256.matches(Regex("[0-9a-f]{64}")) && bytes > 0)
        require(windowSamples == WindowSamples && sampleRate == 16000 && threshold == Threshold)
        require(listOfNotNull(encoderSha256, headSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    val revision: String get() = "$version:$sha256:$PolicyVersion"

    companion object {
        const val Threshold = 0.9120559394359589
        const val WindowSamples = 64000
        const val PolicyVersion = "centered-4s-v1"
    }
}

/** Optional offline asset. Absence is supported; a malformed or incomplete pack is an error. */
object OverlapModelStore {
    fun manifest(context: Context): OverlapManifest? {
        if (context.assets.list("overlap")?.contains("manifest.json") != true) return null
        return context.assets.open("overlap/manifest.json").bufferedReader().use {
            JobFiles.json.decodeFromString<OverlapManifest>(it.readText()).also(OverlapManifest::validate)
        }
    }

    suspend fun prepare(context: Context, manifest: OverlapManifest): File {
        manifest.validate()
        val root = File(context.noBackupFilesDir, "overlap-models/${manifest.sha256}")
        val output = JobFiles.inside(root, "wavlm.onnx")
        currentCoroutineContext().ensureActive()
        if (output.isFile && output.length() == manifest.bytes && JobFiles.sha256(output) == manifest.sha256) return output
        check(root.mkdirs() || root.isDirectory)
        val pending = File.createTempFile(".overlap-", ".pending", root)
        try {
            context.assets.open(manifest.path).use { input ->
                FileOutputStream(pending).use { target ->
                    val buffer = ByteArray(1 shl 20)
                    var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= manifest.bytes) { "Overlap model exceeds manifest length" }
                        target.write(buffer, 0, read)
                    }
                    target.fd.sync()
                }
            }
            require(pending.length() == manifest.bytes && JobFiles.sha256(pending) == manifest.sha256) {
                "Overlap model checksum mismatch"
            }
            currentCoroutineContext().ensureActive()
            Files.move(pending.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return output
        } finally { pending.delete() }
    }
}
