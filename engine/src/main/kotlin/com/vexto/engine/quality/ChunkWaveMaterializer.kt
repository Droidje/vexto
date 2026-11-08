package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.pipeline.ComparisonChunk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

@Serializable data class ChunkStorage(
    val schemaVersion: Int = 1,
    val mode: String = "on-demand",
    val inputWav: String,
    val inputSha256: String,
)

/** Serial per-worker materialization. Source and immutable ASR artifacts are never cleanup targets. */
internal class ChunkWaveMaterializer(
    jobDir: File,
    plan: ChunkPlan,
    expectedInput: File,
    private val readers: List<WorkerStage> = emptyList(),
    private val warning: (String) -> Unit,
) {
    private val job = jobDir.canonicalFile
    private val chunks = java.util.concurrent.ConcurrentHashMap(plan.chunks.associateBy { it.id })
    private val input: File

    init {
        plan.validate()
        val storage = JobFiles.load<ChunkStorage>(File(job, "chunk-storage.json"))
        require(storage.schemaVersion == 1 && storage.mode == "on-demand")
        require(storage.inputSha256.matches(Regex("[0-9a-f]{64}")))
        input = JobFiles.inside(job, storage.inputWav)
        require(input == expectedInput.canonicalFile && !storage.inputWav.startsWith("chunks/"))
        require(PcmWave.inspect(input).sampleCount == plan.sampleCount)
        require(JobFiles.sha256(input) == storage.inputSha256) { "Chunk source hash changed" }
    }

    fun include(plan: ChunkPlan) {
        plan.validate()
        require(plan.sampleCount == PcmWave.inspect(input).sampleCount)
        plan.chunks.forEach { chunk ->
            val old = chunks.putIfAbsent(chunk.id, chunk)
            require(old == null || old == chunk) { "Published PCM chunk changed" }
        }
    }

    suspend fun <T> withChunk(chunk: AudioChunk, block: suspend (ComparisonChunk) -> T): T {
        require(chunks[chunk.id] == chunk) { "Chunk is not part of this plan" }
        currentCoroutineContext().ensureActive()
        val file = checkedPath(chunk)
        var created = false
        file.parentFile?.mkdirs()
        val lock = File(file.parentFile, ".${chunk.id}.lock")
        // Hold a shared read lease through inference. Creation/eviction needs an exclusive
        // lease, so another process cannot remove an input while a native child reads it.
        synchronized(localReaders) { localReaders[file.path] = (localReaders[file.path] ?: 0) + 1 }
        try { RandomAccessFile(lock, "rw").use { owner ->
            while (true) {
                currentCoroutineContext().ensureActive()
                owner.channel.lock(0, Long.MAX_VALUE, true).use {
                    if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()))
                        if (JobFiles.sha256(file) == chunk.inputSha256) {
                            try { return block(ComparisonChunk(chunk.id, file, chunk.inputSha256, chunk.durationMs)) }
                            finally { file.setLastModified(System.currentTimeMillis()) }
                        }
                    }
                }
                owner.channel.lock().use {
                    if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()))
                        if (JobFiles.sha256(file) != chunk.inputSha256) JobCheckpoint.archiveRetryOutput(job, file)
                    }
                    if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                        Files.createFile(file.toPath())
                        created = true
                        PcmWave.crop(input, file, chunk.fromSample, chunk.toSample)
                    }
                }
            }
        } } finally {
            synchronized(localReaders) {
                val count = requireNotNull(localReaders[file.path]) - 1
                if (count == 0) localReaders.remove(file.path) else localReaders[file.path] = count
            }
            if (readers.isEmpty() && created) evict(file)
        }
    }

    /** A published candidate proves that consumer finished reading and verifying this WAV. */
    fun releaseCompleted(completed: Iterable<AudioChunk>) {
        if (readers.isNotEmpty()) {
            val durable = readers.map { stage -> runCatching {
                JobFiles.load<WorkerResult>(stage.resultFile(job)).candidates.filter {
                    it.sha256.matches(Regex("[a-f0-9]{64}"))
                }.mapTo(mutableSetOf()) { it.chunkId }
            }.getOrDefault(emptySet()) }.reduce { a, b -> a.intersect(b) }
            completed.filter { it.id in durable }.forEach { evict(checkedPath(it)) }
        }
        // Re-creatable PCM cache is bounded even when one recognizer gets far ahead.
        val files = File(job, "chunks").listFiles().orEmpty().filter {
            it.name.matches(Regex("chunk_[0-9]{6}\\.wav")) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)
        }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= 64L * 1024 * 1024) break
            val length = file.length()
            if (evict(file)) bytes -= length
        }
    }

    private fun tryExclusive(channel: FileChannel): FileLock? = try { channel.tryLock() }
        catch (_: OverlappingFileLockException) { null }

    private fun evict(file: File): Boolean {
        synchronized(localReaders) {
            // POSIX process locks can be released by closing another fd for that inode.
            // Never open an eviction fd while this JVM already owns its read lease.
            if (file.path in localReaders) return false
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return false
            try {
                RandomAccessFile(File(file.parentFile, ".${file.nameWithoutExtension}.lock"), "rw").use { owner ->
                    val lease = tryExclusive(owner.channel) ?: return false
                    lease.use {
                        if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                            require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()))
                            Files.delete(file.toPath())
                            return true
                        }
                    }
                }
            } catch (failure: Exception) { warning("CHUNK_CLEANUP_FAILED:${file.name}:${failure.javaClass.simpleName}") }
            return false
        }
    }

    private companion object { val localReaders = mutableMapOf<String, Int>() }

    private fun checkedPath(chunk: AudioChunk): File {
        require(chunk.id.matches(Regex("chunk_[0-9]{6}")))
        require(chunk.wav == "chunks/${chunk.id}.wav")
        val expected = File(job, chunk.wav).absoluteFile
        val canonical = JobFiles.inside(job, chunk.wav)
        require(canonical == expected && canonical.parentFile == File(job, "chunks") && canonical != input) {
            "Unsafe generated chunk path"
        }
        return canonical
    }
}
