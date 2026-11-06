package com.vexto.engine.models

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/**
 * Копирует модели из assets APK в filesDir один раз после установки или обновления набора.
 * Пути к настоящим файлам нужны sherpa-onnx, а чтение из assets держало бы копию модели в памяти.
 */
object ModelStore {

    private const val AssetDir = "models"
    private const val Marker = ".installed"

    fun manifest(context: Context): ModelManifest = try {
        context.assets.open("$AssetDir/manifest.json").bufferedReader().use { ModelManifest.parse(it.readText()) }
    } catch (e: IOException) {
        throw IOException("В APK нет моделей: перед сборкой запустите scripts/fetch_models.sh", e)
    }

    @JvmStatic
    fun isInstalled(context: Context): Boolean {
        val dir = File(context.filesDir, AssetDir)
        return File(dir, Marker).takeIf { it.exists() }?.readText() == manifest(context).version
    }

    suspend fun install(context: Context, onProgress: (Float) -> Unit): InstalledModels {
        val manifest = manifest(context)
        val dir = File(context.filesDir, AssetDir)
        val marker = File(dir, Marker)
        if (marker.exists() && marker.readText() == manifest.version) return InstalledModels(dir, manifest)

        dir.deleteRecursively()
        dir.mkdirs()
        val sizes = manifest.files.associateWith { name ->
            context.assets.openFd("$AssetDir/$name").use { it.length }
        }
        val total = sizes.values.sum()
        val free = StatFs(context.filesDir.path).availableBytes
        if (free < total + ReserveBytes) {
            throw IOException("Нужно ${(total + ReserveBytes) shr 20} МБ свободного места, доступно ${free shr 20} МБ")
        }
        var copied = 0L
        val buffer = ByteArray(1 shl 20)
        for (name in manifest.files) {
            val target = File(dir, name)
            target.parentFile?.mkdirs()
            context.assets.open("$AssetDir/$name").use { input ->
                target.outputStream().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        onProgress(copied.toFloat() / total)
                    }
                }
            }
        }
        marker.writeText(manifest.version)
        return InstalledModels(dir, manifest)
    }

    /** Запас под временный PCM-файл и рабочие файлы приложения. */
    private const val ReserveBytes = 300L shl 20
}
