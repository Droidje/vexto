package com.vexto.llm

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Где лежит GGUF. llama.cpp читает модель через mmap по пути к файлу, а файл из APK
 * так открыть нельзя, поэтому модель из assets один раз копируется в filesDir.
 */
internal object ModelFiles {

    private const val ASSET_DIR = "models"

    fun resolve(context: Context, explicitPath: String?): File {
        if (!explicitPath.isNullOrEmpty()) {
            val f = File(explicitPath)
            if (!f.canRead()) throw LlmException("модель недоступна: $explicitPath")
            return f
        }
        val name = context.assets.list(ASSET_DIR)?.firstOrNull { it.endsWith(".gguf") }
            ?: throw LlmException("в APK нет модели: запустите scripts/fetch_llm_model.sh и пересоберите")
        val asset = "$ASSET_DIR/$name"
        val target = File(File(context.filesDir, ASSET_DIR), name)
        // Копия появляется через rename, поэтому существующий файл всегда полный.
        if (target.exists()) return target

        val dir = target.parentFile!!
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val size = runCatching { context.assets.openFd(asset).use { it.length } }.getOrNull()
        val free = StatFs(dir.path).availableBytes
        if (size != null && free < size) {
            throw LlmException("мало места для модели: нужно ${size shr 20} МБ, свободно ${free shr 20} МБ")
        }

        val tmp = File(target.path + ".part")
        context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (!tmp.renameTo(target)) throw LlmException("не удалось сохранить модель в ${target.path}")
        return target
    }
}
