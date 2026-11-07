package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StreamingRecognitionTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun failedDiarizationClosesWaitingAsrBeforeSerialRetry() = runBlocking {
        val dir = tmp.newFolder("memory")
        val input = File(dir, "original.wav")
        PcmWave.Writer(input).use { it.write(FloatArray(110 * 16000) { .1f }) }
        JobFiles.save(File(dir, "diarization-progress.json"),
            DiarizationProgress(65_000, listOf(PipelineTurn(0, 0, 65_000))))
        val diarization = CompletableDeferred<WorkerResult>()
        val entered = mutableSetOf<WorkerStage>()
        val result = withTimeout(5000) {
            StreamingRecognition.run(dir, input, diarization, slavic = true) { stage, initial ->
                entered += stage
                if (entered.size == 2) diarization.complete(WorkerResult(WorkerStage.DIARIZE,
                    error = "MEMORY_BUDGET_EXCEEDED"))
                try {
                    StreamingRecognition.tasks(dir, initial, emptySet()) {}.collect {}
                    WorkerResult(stage, completed = true)
                } catch (_: IllegalArgumentException) {
                    WorkerResult(stage, error = "STREAM_ABORTED")
                }
            }
        }
        assertEquals(setOf(WorkerStage.GIGAAM, WorkerStage.WHISPER), result.keys)
        assertTrue(result.values.all { !it.completed && it.error == "STREAM_ABORTED" })
    }

    @Test fun livePrefixIsIdenticalToFinalGeometryAndClosesTheQueue() = runBlocking {
        val dir = tmp.newFolder("stream")
        val input = File(dir, "original.wav")
        PcmWave.Writer(input).use { it.write(FloatArray(110 * 16000) { .1f }) }
        val publisher = StreamingChunkPublisher(dir, input)
        val first = publisher.advance(DiarizationProgress(65_000, listOf(PipelineTurn(0, 0, 65_000))))!!
        assertEquals(2, first.chunks.size)
        val second = publisher.advance(DiarizationProgress(95_000, listOf(PipelineTurn(0, 0, 95_000))))!!
        assertEquals(first.chunks, second.chunks.take(2))
        val final = ChunkPlan.create(input, dir, listOf(PipelineTurn(0, 0, 110_000)))
        publisher.finish(final, true)
        val tasks = StreamingRecognition.tasks(dir, first, setOf(first.chunks.first().id)) {}.toList()
        assertEquals(final.chunks.drop(1), tasks)
        assertFalse(JobFiles.load<StreamingChunks>(publisher.snapshot).aborted)
    }

    @Test fun revisedPrefixAbortsInsteadOfMixingOldAudioWithNewIds() = runBlocking {
        val dir = tmp.newFolder("revised")
        val input = File(dir, "original.wav")
        PcmWave.Writer(input).use { it.write(FloatArray(70 * 16000) { .1f }) }
        val publisher = StreamingChunkPublisher(dir, input)
        val first = publisher.advance(DiarizationProgress(65_000, listOf(PipelineTurn(0, 0, 65_000))))!!
        val revised = ChunkPlan.create(input, dir, listOf(PipelineTurn(0, 2000, 70_000)))
        publisher.finish(revised, true)
        assertTrue(JobFiles.load<StreamingChunks>(publisher.snapshot).aborted)
        assertTrue(runCatching { StreamingRecognition.tasks(dir, first, emptySet()) {}.toList() }.isFailure)
    }
}
