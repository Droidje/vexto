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
        try {
            val parent = requireNotNull(file.parentFile)
            check(parent.mkdirs() || parent.isDirectory)
            require(checkedPath(chunk) == file)
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                // A killed previous worker may have left the exact verified chunk behind.
                // Reuse it for fallback ASR, retaining this non-owned file for audit.
                require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()))
                if (JobFiles.sha256(file) != chunk.inputSha256) JobCheckpoint.archiveRetryOutput(job, file)
            }
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                Files.createFile(file.toPath()) // Reserve a fresh path; never overwrite another file.
                created = true
                PcmWave.crop(input, file, chunk.fromSample, chunk.toSample)
            }
            currentCoroutineContext().ensureActive()
            // Existing providers validate this SHA and fsync the immutable candidate before returning.
            return block(ComparisonChunk(chunk.id, file, chunk.inputSha256, chunk.durationMs))
        } finally {
            if (created) {
                try {
                    require(checkedPath(chunk) == file)
                    if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                        Files.delete(file.toPath())
                    }
                } catch (failure: Exception) {
                    // A retained temporary must not erase an already durable ASR result.
                    warning("CHUNK_CLEANUP_FAILED:${chunk.id}:${failure.javaClass.simpleName}")
                }
            }
        }
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
