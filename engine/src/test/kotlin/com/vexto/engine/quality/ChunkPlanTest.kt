package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChunkPlanTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun terminalOneToFifteenSamplesAreRetainedWithoutZeroDurationChunks() = runBlocking {
        for (tail in 1..15) {
            val directory = temporary.newFolder()
            val source = File(directory, "source.wav")
            val count = ChunkPlan.MaxSamples.toInt() + tail
            PcmWave.Writer(source).use { it.write(FloatArray(count) { .125f }) }
            val plan = ChunkPlan.create(source, directory)
            assertEquals(2, plan.chunks.size)
            assertEquals(16L, plan.chunks.last().toSample - plan.chunks.last().fromSample)
            assertTrue(plan.chunks.all { it.durationMs >= 1 && it.toSample - it.fromSample <= ChunkPlan.MaxSamples })
            assertExactPcm(source, directory, plan)
        }
    }

    @Test fun wholeSubMillisecondFilesKeepTheirExactSamplesAndExposeOnlyRoundedBounds() = runBlocking {
        for (count in 1..15) {
            val directory = temporary.newFolder()
            val source = File(directory, "source.wav")
            PcmWave.Writer(source).use { it.write(FloatArray(count) { (it - 7) / 32768f }) }
            val plan = ChunkPlan.create(source, directory)
            val chunk = plan.chunks.single()
            assertEquals(count.toLong(), plan.sampleCount)
            assertEquals(0L, chunk.fromSample)
            assertEquals(count.toLong(), chunk.toSample)
            assertEquals(1L, chunk.durationMs) // An upper bound, not an invented millisecond of PCM.
            assertExactPcm(source, directory, plan)
        }
    }

    @Test fun bufferedQuietSearchPreservesLegacyBoundariesAndExactPcm() = runBlocking {
        // Include dense speech, silence, a quiet region near the earlier search limit,
        // and amplitude just above/below the existing energy threshold.
        for (kind in 0..4) {
            val directory = temporary.newFolder()
            val source = File(directory, "source.wav")
            val samples = FloatArray(16_000 * 24 + 99) { i ->
                when (kind) {
                    0 -> .125f
                    1 -> 0f
                    2 -> if (i % 160000 in 82000..85500) 0f else .125f
                    3 -> if (i % 160000 > 120000) .0099f else .125f
                    else -> if (i % 160000 > 120000) .0101f else .125f
                }
            }
            PcmWave.Writer(source).use { it.write(samples) }
            val expected = legacyBoundaries(source)
            val plan = ChunkPlan.create(source, directory)
            assertEquals(expected, plan.chunks.map { it.toSample })
            assertExactPcm(source, directory, plan)
        }
    }

    /** Previous uncached search is the behavior reference, including Float-square summation order. */
    private fun legacyBoundaries(source: File): List<Long> {
        val count = PcmWave.inspect(source).sampleCount
        val boundaries = mutableListOf<Long>()
        var from = 0L
        while (from < count) {
            var to = minOf(from + ChunkPlan.MaxSamples, count)
            if (to < count) {
                var at = to - 3200
                while (at >= from + ChunkPlan.MaxSamples / 2) {
                    val values = PcmWave.readFloat(source, at, 3200)
                    val energy = values.fold(0.0) { sum, value -> sum + value * value } / values.size
                    if (energy < .0001) { to = at + 1600; break }
                    at -= 1600
                }
            }
            boundaries += to
            from = to
        }
        return boundaries
    }

    private suspend fun assertExactPcm(source: File, directory: File, plan: ChunkPlan) {
        plan.validate()
        val expected = source.readBytes().copyOfRange(44, source.length().toInt())
        val waves = ChunkWaveMaterializer(directory, plan, source) { fail(it) }
        assertTrue(plan.chunks.none { JobFiles.inside(directory, it.wav).exists() })
        val joined = plan.chunks.flatMap { chunk ->
            val wav = JobFiles.inside(directory, chunk.wav)
            val pcm = waves.withChunk(chunk) { current ->
                assertEquals(wav, current.wav)
                assertTrue(current.wav.isFile)
                assertEquals(chunk.inputSha256, current.inputSha256)
                assertEquals(chunk.inputSha256, JobFiles.sha256(current.wav))
                current.wav.readBytes().drop(44)
            }
            assertFalse(wav.exists())
            pcm
        }.toByteArray()
        assertArrayEquals(expected, joined)
    }
}
