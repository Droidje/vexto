package com.vexto.engine.review

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only complete bounded responses are committed; the directory belongs to one recording. */
internal class SummaryCheckpoints(root: File, identity: String) {
    private val directory = File(root, hash(identity))

    init {
        check(root.mkdirs() || root.isDirectory)
        root.listFiles().orEmpty().filter { it != directory }.forEach { it.deleteRecursively() }
        check(directory.mkdirs() || directory.isDirectory)
        val expired = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
            if (index >= 1024 || file.lastModified() < expired || file.name.endsWith(".pending")) file.delete()
        }
    }

    suspend fun response(input: String, generate: suspend () -> String): String {
        val target = File(directory, hash(input))
        if (target.isFile && target.length() in 1..SummaryPlan.OutputBytes.toLong()) {
            val saved = target.readText()
            if (saved.isNotBlank()) return saved
        }
        val result = generate()
        currentCoroutineContext().ensureActive()
        require(result.isNotBlank() && result.toByteArray().size <= SummaryPlan.OutputBytes)
        val pending = File(directory, "${target.name}.pending")
        java.io.FileOutputStream(pending).use { output ->
            output.write(result.toByteArray()); output.fd.sync()
        }
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        directory.listFiles().orEmpty().filter { !it.name.endsWith(".pending") }
            .sortedByDescending { it.lastModified() }.drop(1024).forEach { it.delete() }
        return result
    }

    companion object {
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
