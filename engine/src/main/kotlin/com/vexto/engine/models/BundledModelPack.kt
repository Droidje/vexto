package com.vexto.engine.models

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class BundledModelPackException(cause: IOException) : IOException(
    "Не удалось подготовить встроенные модели. Проверьте свободное место и полноту комплекта APK.",
    cause,
)

internal object BundledModelPack {
    private const val SplitPrefix = "vexto_models_"
    private const val AssetDir = "vexto-models"

    fun installed(context: Context): Boolean =
        context.applicationInfo.splitNames?.any { it.startsWith(SplitPrefix) } == true

    /** The engine serializes preparation before starting any worker that reads these files. */
    suspend fun prepare(
        context: Context,
        manifestDigest: String,
        artifacts: List<QualityArtifact>,
        files: List<File>,
        verificationCache: ModelVerificationCache,
        progress: (Float) -> Unit,
    ): Boolean {
        val coroutine = currentCoroutineContext()
        val copied = mutableSetOf<Int>()
        fun copy(index: Int) {
            copyVerifiedAsset(context, artifacts[index], files[index], coroutine::ensureActive)
            copied += index
        }
        fun verify(): Boolean = verificationCache.verify(manifestDigest, files) {
            val damaged = files.indices.filter { index ->
                coroutine.ensureActive()
                val artifact = artifacts[index]
                val file = files[index]
                !file.isFile || file.length() != artifact.bytes ||
                    (index !in copied && file.inputStream().use { hash(it, coroutine::ensureActive) } != artifact.sha256)
            }
            if (damaged.isNotEmpty()) throw DamagedModels(damaged)
        }

        return try {
            files.forEachIndexed { index, file ->
                coroutine.ensureActive()
                if (!file.isFile || file.length() != artifacts[index].bytes) copy(index)
                progress(0.75f * (index + 1) / files.size)
            }
            val hit = try {
                verify()
            } catch (damaged: DamagedModels) {
                damaged.indices.forEach(::copy)
                verify()
            }
            coroutine.ensureActive()
            progress(1f)
            hit
        } catch (error: IOException) {
            throw BundledModelPackException(error)
        }
    }

    private class DamagedModels(val indices: List<Int>) : IOException("Bundled model integrity check failed")

    private fun copyVerifiedAsset(context: Context, artifact: QualityArtifact, file: File, checkActive: () -> Unit) {
        val parent = requireNotNull(file.parentFile)
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create model directory")
        // An interrupted process may leave a partial file. A retry truncates that same file.
        val pending = File(parent, ".${file.name}.pending")
        try {
            var bytes = 0L
            // The release stores models uncompressed so a bounded stream can read the ZIP range.
            context.assets.openFd("$AssetDir/${artifact.path}").use { asset ->
                if (asset.length != artifact.bytes) throw IOException("Invalid bundled model size: ${artifact.path}")
                asset.createInputStream().use { input ->
                    FileOutputStream(pending).use { output ->
                        val digest = hash(input, checkActive) { buffer, size ->
                            bytes += size
                            if (bytes > artifact.bytes) throw IOException("Oversized bundled model: ${artifact.path}")
                            output.write(buffer, 0, size)
                        }
                        if (bytes != artifact.bytes || digest != artifact.sha256) {
                            throw IOException("Invalid bundled model: ${artifact.path}")
                        }
                        output.fd.sync()
                    }
                }
            }
            checkActive()
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }

    private fun hash(
        input: InputStream,
        checkActive: () -> Unit,
        consume: (ByteArray, Int) -> Unit = { _, _ -> },
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        while (true) {
            checkActive()
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
            consume(buffer, size)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
