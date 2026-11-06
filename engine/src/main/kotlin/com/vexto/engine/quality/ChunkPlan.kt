package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
data class AudioChunk(val id: String, val fromSample: Long, val toSample: Long, val wav: String, val inputSha256: String) {
    val startMs: Long get() = fromSample * 1000 / 16000
    val durationMs: Long get() = (toSample - fromSample) * 1000 / 16000
}

@Serializable
data class ChunkPlan(val sampleCount: Long, val chunks: List<AudioChunk>) {
    fun validate() {
        var cursor = 0L
        chunks.forEachIndexed { index, chunk ->
            require(chunk.fromSample == cursor && chunk.toSample > cursor && chunk.toSample - cursor <= MaxSamples)
            require(chunk.id == "chunk_%06d".format(index))
            cursor = chunk.toSample
        }
        require(cursor == sampleCount)
    }
    companion object {
        const val MaxSamples = 10 * 16000L
        /** Quiet-boundary snapping, not speech removal: every input sample belongs to exactly one chunk. */
        suspend fun create(input: File, jobDir: File): ChunkPlan {
            val count = PcmWave.inspect(input).sampleCount
            val result = mutableListOf<AudioChunk>()
            var from = 0L
            while (from < count) {
                currentCoroutineContext().ensureActive()
                var to = minOf(from + MaxSamples, count)
                if (to < count) {
                    // Last quiet 200ms in the second half; bound model prompt and RAM to <=10s.
                    var at = to - 3200
                    while (at >= from + MaxSamples / 2) {
                        val samples = PcmWave.readFloat(input, at, 3200)
                        val energy = samples.fold(0.0) { sum, value -> sum + value * value } / samples.size
                        if (energy < 0.0001) { to = at + 1600; break }
                        at -= 1600
                    }
                }
                val id = "chunk_%06d".format(result.size)
                val relative = "chunks/$id.wav"
                val wav = JobFiles.inside(jobDir, relative)
                PcmWave.crop(input, wav, from, to)
                result += AudioChunk(id, from, to, relative, JobFiles.sha256(wav))
                from = to
            }
            return ChunkPlan(count, result).also { it.validate(); JobFiles.save(File(jobDir, "chunks.json"), it) }
        }
    }
}
