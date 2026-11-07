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
data class SampleRange(val fromSample: Long, val toSample: Long)

@Serializable
data class ChunkPlan(val sampleCount: Long, val chunks: List<AudioChunk>, val omittedRanges: List<SampleRange> = emptyList()) {
    val skippedSamples: Long get() = omittedRanges.sumOf { it.toSample - it.fromSample }

    fun validate() {
        require(sampleCount >= 0)
        var previousEnd = 0L
        chunks.forEachIndexed { index, chunk ->
            require(chunk.fromSample >= previousEnd && chunk.toSample > chunk.fromSample &&
                chunk.toSample <= sampleCount && chunk.toSample - chunk.fromSample <= MaxSamples)
            require(chunk.id == "chunk_%06d".format(index))
            previousEnd = chunk.toSample
        }
        var cursor = 0L
        val coverage = chunks.map { SampleRange(it.fromSample, it.toSample) } + omittedRanges
        coverage.sortedBy { it.fromSample }.forEach { range ->
            require(range.fromSample == cursor && range.toSample > cursor && range.toSample <= sampleCount)
            cursor = range.toSample
        }
        require(cursor == sampleCount)
    }
    companion object {
        /** GigaAM accepts 30s. A 10s cut raised WER on the 30s Russian clips from 32.5% to 36.4%. */
        const val MaxSamples = 30 * 16000L
        /** Null turns retain legacy full coverage; an explicitly empty successful diarization skips silence. */
        suspend fun create(input: File, jobDir: File, turns: List<PipelineTurn>? = null): ChunkPlan {
            val count = PcmWave.inspect(input).sampleCount
            val job = jobDir.canonicalFile
            val source = input.canonicalFile
            val inputWav = source.relativeTo(job).invariantSeparatorsPath
            require(JobFiles.inside(job, inputWav) == source && !inputWav.startsWith("chunks/"))
            val storage = ChunkStorage(inputWav = inputWav, inputSha256 = JobFiles.sha256(source))
            val result = mutableListOf<AudioChunk>()
            val regions = speechRegions(count, turns)
            val omitted = mutableListOf<SampleRange>()
            var cursor = 0L
            for (region in regions) {
                if (cursor < region.fromSample) omitted += SampleRange(cursor, region.fromSample)
                var from = region.fromSample
                while (from < region.toSample) {
                    currentCoroutineContext().ensureActive()
                    var to = minOf(from + MaxSamples, region.toSample)
                    if (to < region.toSample) {
                        // Last quiet 200ms in the second half. One chunk stays within 30s.
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
                        if (region.toSample - to in 1L..15L) to = region.toSample - 16
                    }
                    val id = "chunk_%06d".format(result.size)
                    val relative = "chunks/$id.wav"
                    result += AudioChunk(id, from, to, relative, PcmWave.cropSha256(source, from, to))
                    from = to
                }
                cursor = region.toSample
            }
            if (cursor < count) omitted += SampleRange(cursor, count)
            return ChunkPlan(count, result, omitted).also {
                it.validate()
                JobFiles.save(File(job, "chunk-storage.json"), storage)
                JobFiles.save(File(job, "chunks.json"), it)
            }
        }

        /** Keep 300ms of acoustic context, merge nearby turns, and retain the original sample clock. */
        internal fun speechRegions(count: Long, turns: List<PipelineTurn>?): List<SampleRange> {
            require(count >= 0)
            if (turns == null) return if (count == 0L) emptyList() else listOf(SampleRange(0, count))
            val merged = mutableListOf<SampleRange>()
            turns.sortedBy { it.startMs }.forEach { turn ->
                require(turn.startMs >= 0 && turn.endMs > turn.startMs)
                val range = SampleRange(((turn.startMs - 300).coerceAtLeast(0) * 16).coerceAtMost(count),
                    (turn.endMs.coerceAtMost(count / 16 + 1) * 16 + 4800).coerceAtMost(count))
                if (range.toSample <= range.fromSample) return@forEach
                val previous = merged.lastOrNull()
                if (previous != null && range.fromSample <= previous.toSample + 8000) {
                    merged[merged.lastIndex] = previous.copy(toSample = maxOf(previous.toSample, range.toSample))
                } else merged += range
            }
            return merged
        }
    }
}
