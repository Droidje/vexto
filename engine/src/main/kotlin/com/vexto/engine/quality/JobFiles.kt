package com.vexto.engine.quality

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Atomic durable artifacts; model output text is never used as a filesystem path. */
object JobFiles {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun write(file: File, bytes: ByteArray) {
        val parent = requireNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val temporary = File.createTempFile(".artifact-", ".pending", parent)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    inline fun <reified T> save(file: File, value: T) = write(file, json.encodeToString(value).toByteArray(Charsets.UTF_8))
    inline fun <reified T> load(file: File): T = json.decodeFromString(file.readText(Charsets.UTF_8))
    fun inside(root: File, relative: String): File {
        require(relative.isNotEmpty() && !File(relative).isAbsolute)
        val canonical = root.canonicalFile
        val file = File(canonical, relative).canonicalFile
        require(file.path.startsWith(canonical.path + File.separator)) { "Path outside private job" }
        return file
    }
}
