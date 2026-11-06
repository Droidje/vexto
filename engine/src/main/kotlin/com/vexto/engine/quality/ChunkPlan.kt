package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
data class AudioChunk(val id: String, val fromSample: Long, val toSample: Long, val wav: String, val inputSha256: String) {
    val startMs: Long get() = fromSample * 1000 / 16000
    /** Millisecond upper bound for model/word bounds; sample endpoints remain the exact clock. */
    val durationMs: Long get() = (toSample - fromSample + 15) / 16
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
                    val windowStart = from + MaxSamples / 2
                    val window = PcmWave.readFloat(input, windowStart, (to - windowStart).toInt())
                    var at = to - 3200
                    while (at >= windowStart) {
                        val offset = (at - windowStart).toInt()
                        var sum = 0.0
                        // Preserve the previous left-to-right Float-square/Double-sum arithmetic.
                        for (i in offset until offset + 3200) sum += window[i] * window[i]
                        val energy = sum / 3200
                        if (energy < 0.0001) { to = at + 1600; break }
                        at -= 1600
                    }
                    // Do not isolate a sub-millisecond terminal WAV. Move the boundary,
                    // retaining every PCM sample and the existing per-chunk maximum.
                    if (count - to in 1L..15L) to = count - 16
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
