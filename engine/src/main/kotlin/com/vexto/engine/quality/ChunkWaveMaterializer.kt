package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.pipeline.ComparisonChunk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

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
    private val chunks = plan.chunks.associateBy { it.id }
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

    suspend fun <T> withChunk(chunk: AudioChunk, block: suspend (ComparisonChunk) -> T): T {
        require(chunks[chunk.id] == chunk) { "Chunk is not part of this plan" }
        currentCoroutineContext().ensureActive()
        val file = checkedPath(chunk)
        var created = false
        file.parentFile?.mkdirs()
        val lock = File(file.parentFile, ".${chunk.id}.lock")
        // File locks synchronize independent Android processes, not just coroutine consumers.
        java.io.RandomAccessFile(lock, "rw").use { owner -> owner.channel.lock().use {
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()))
                if (JobFiles.sha256(file) != chunk.inputSha256) JobCheckpoint.archiveRetryOutput(job, file)
            }
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                Files.createFile(file.toPath())
                created = true
                PcmWave.crop(input, file, chunk.fromSample, chunk.toSample)
            }
        } }
        try {
            currentCoroutineContext().ensureActive()
            return block(ComparisonChunk(chunk.id, file, chunk.inputSha256, chunk.durationMs))
        } finally {
            if (readers.isEmpty() && created) Files.deleteIfExists(file.toPath())
        }
    }

    /** A published candidate proves that consumer finished reading and verifying this WAV. */
    fun releaseCompleted(chunk: AudioChunk) {
        if (readers.isEmpty() || !readers.all { stage -> runCatching {
                JobFiles.load<WorkerResult>(stage.resultFile(job)).candidates.any {
                    it.chunkId == chunk.id && it.sha256.matches(Regex("[a-f0-9]{64}"))
                }
            }.getOrDefault(false) }) return
        val file = checkedPath(chunk)
        try {
            java.io.RandomAccessFile(File(file.parentFile, ".${chunk.id}.lock"), "rw").use { owner ->
                owner.channel.lock().use {
                    if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()))
                        Files.delete(file.toPath())
                    }
                }
            }
        } catch (failure: Exception) { warning("CHUNK_CLEANUP_FAILED:${chunk.id}:${failure.javaClass.simpleName}") }
    }

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
