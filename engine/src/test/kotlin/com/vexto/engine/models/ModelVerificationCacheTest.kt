package com.vexto.engine.models

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit.NANOSECONDS

class ModelVerificationCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun model(name: String = "model"): File = temporary.newFile(name).apply { writeText("abcd") }

    @Test fun firstVerificationHashesEveryFileAndUnchangedFilesReuseIt() {
        val files = listOf(model("first"), model("second"))
        var hashed = 0
        val cache = ModelVerificationCache()
        fun verify() = cache.verify("manifest-digest", files) { files.forEach { it.readBytes(); hashed++ } }
        assertFalse(verify())
        assertEquals(2, hashed)
        assertTrue(verify())
        assertEquals(2, hashed)
        files[1].appendText("e")
        assertFalse(verify())
        assertEquals(4, hashed) // Any changed file invalidates the full pack.
    }

    @Test fun sameSizeChangeWithDifferentMtimeRequiresFullVerification() {
        val file = model()
        var hashed = 0
        val cache = ModelVerificationCache()
        fun verify() = cache.verify("manifest", listOf(file)) { file.readBytes(); hashed++ }
        assertFalse(verify())
        val previous = Files.getLastModifiedTime(file.toPath())
        file.writeText("wxyz")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(previous.toMillis() + 2000))
        assertFalse(verify())
        assertEquals(2, hashed)
    }

    @Test fun mtimeComparisonRetainsSubMillisecondPrecision() {
        val file = model()
        val first = ModelVerificationCache.readSnapshot(file).copy(fileKey = "inode",
            lastModifiedTime = FileTime.from(1_000_000_001, NANOSECONDS))
        var snapshot = first
        var hashed = 0
        val cache = ModelVerificationCache { snapshot }
        assertFalse(cache.verify("manifest", listOf(file)) { hashed++ })
        snapshot = first.copy(lastModifiedTime = FileTime.from(1_000_000_002, NANOSECONDS))
        assertEquals(first.lastModifiedTime.toMillis(), snapshot.lastModifiedTime.toMillis())
        assertFalse(cache.verify("manifest", listOf(file)) { hashed++ })
        assertEquals(2, hashed)
    }

    @Test fun atomicReplacementWithSameSizeAndMtimeInvalidatesTheInode() {
        val file = model()
        val original = ModelVerificationCache.readSnapshot(file)
        assumeNotNull(original.fileKey)
        val cache = ModelVerificationCache()
        var hashed = 0
        assertFalse(cache.verify("manifest", listOf(file)) { hashed++ })
        val replacement = model("replacement").apply { writeText("wxyz") }
        Files.setLastModifiedTime(replacement.toPath(), original.lastModifiedTime)
        Files.move(replacement.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        val replaced = ModelVerificationCache.readSnapshot(file)
        assertEquals(original.size, replaced.size)
        assertEquals(original.lastModifiedTime, replaced.lastModifiedTime)
        assertNotEquals(original.fileKey, replaced.fileKey)
        assertFalse(cache.verify("manifest", listOf(file)) { hashed++ })
        assertEquals(2, hashed)
    }

    @Test fun exactManifestContentChangeInvalidatesEvenWhenVersionIsTheSame() {
        val file = model()
        val cache = ModelVerificationCache()
        val first = "{\"version\":\"same\",\"artifacts\":{}}".toByteArray()
        val changed = "{\"version\":\"same\",\"artifacts\":{\"voiceprint\":{}}}".toByteArray()
        var hashed = 0
        fun verify(bytes: ByteArray) = cache.verify(ModelVerificationCache.manifestDigest(bytes), listOf(file)) { hashed++ }
        assertFalse(verify(first))
        assertTrue(verify(first))
        assertFalse(verify(changed))
        assertEquals(2, hashed)
    }

    @Test fun failedVerificationIsNeverCached() {
        val file = model()
        val cache = ModelVerificationCache()
        var attempts = 0
        assertThrows(IOException::class.java) {
            cache.verify("manifest", listOf(file)) { attempts++; throw IOException("SHA mismatch") }
        }
        assertFalse(cache.verify("manifest", listOf(file)) { attempts++ })
        assertTrue(cache.verify("manifest", listOf(file)) { attempts++ })
        assertEquals(2, attempts)
    }

    @Test fun changeDuringFullVerificationFailsExplicitlyAndClearsTheCache() {
        val file = model()
        val cache = ModelVerificationCache()
        val failure = assertThrows(IOException::class.java) {
            cache.verify("manifest", listOf(file)) { file.appendText("changed during hashing") }
        }
        assertEquals("MODEL_CHANGED_DURING_VERIFICATION", failure.message)
        var hashed = 0
        assertFalse(cache.verify("manifest", listOf(file)) { hashed++ })
        assertEquals(1, hashed)
    }

    @Test fun missingInodeSymlinkAndNonregularFilesCannotProduceCacheHits() {
        val file = model()
        val valid = ModelVerificationCache.readSnapshot(file).copy(fileKey = "inode")
        for (snapshot in listOf(valid.copy(fileKey = null), valid.copy(symbolicLink = true), valid.copy(regularFile = false))) {
            val cache = ModelVerificationCache { snapshot }
            var hashed = 0
            assertFalse(cache.verify("manifest", listOf(file)) { hashed++ })
            assertFalse(cache.verify("manifest", listOf(file)) { hashed++ })
            assertEquals(2, hashed)
        }
    }
}
