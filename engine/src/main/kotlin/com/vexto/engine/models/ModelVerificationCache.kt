package com.vexto.engine.models

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Process-local IO optimization, not protection against an adversary preserving inode/mtime. */
internal class ModelVerificationCache(
    private val snapshot: (File) -> Snapshot = ::readSnapshot,
) {
    internal data class Snapshot(
        val canonicalPath: String,
        val size: Long,
        val lastModifiedTime: FileTime,
        val fileKey: Any?,
        val regularFile: Boolean,
        val symbolicLink: Boolean,
    ) {
        val cacheable: Boolean get() = regularFile && !symbolicLink && fileKey != null
    }

    private data class Verified(val manifestDigest: String, val files: List<Snapshot>)
    private var verified: Verified? = null

    /** Returns true only when this exact manifest and every file retain a successful verification. */
    @Synchronized
    fun verify(manifestDigest: String, files: List<File>, verifyAll: () -> Unit): Boolean {
        try {
            val before = files.map(snapshot)
            val cached = verified
            if (before.all { it.cacheable } && cached != null && cached.manifestDigest == manifestDigest && cached.files == before) {
                return true
            }
            verified = null
            verifyAll()
            val after = try { files.map(snapshot) }
            catch (failure: IOException) { throw IOException("MODEL_CHANGED_DURING_VERIFICATION", failure) }
            if (before != after) throw IOException("MODEL_CHANGED_DURING_VERIFICATION")
            if (after.all { it.cacheable }) verified = Verified(manifestDigest, after)
            return false
        } catch (failure: Throwable) {
            verified = null
            throw failure
        }
    }

    internal companion object {
        fun manifestDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun readSnapshot(file: File): Snapshot {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            return Snapshot(file.canonicalPath, attributes.size(), attributes.lastModifiedTime(),
                attributes.fileKey(), attributes.isRegularFile, attributes.isSymbolicLink)
        }
    }
}
