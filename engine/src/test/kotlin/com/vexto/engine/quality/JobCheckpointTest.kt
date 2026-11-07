package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.models.QualityManifest
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.ImmutableAsrCandidate
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JobCheckpointTest {
    @get:Rule val temp = TemporaryFolder()
    private val manifest = QualityManifest("test", emptyMap())

    @Test fun resumedJobRejectsAudioOrModelChanges() {
        val jobs = temp.newFolder()
        val audio = temp.newFile().apply { writeText("first") }
        val id = UUID.randomUUID().toString()
        val job = JobCheckpoint.open(jobs, id, audio, manifest, null)
        assertEquals(job.directory, JobCheckpoint.open(jobs, id, audio, manifest, null).directory)
        assertThrows(IOException::class.java) {
            JobCheckpoint.open(jobs, id, audio, manifest.copy(version = "another"), null)
        }
        assertThrows(IOException::class.java) {
            JobCheckpoint.open(jobs, id, audio, manifest, null, com.vexto.engine.model.ProcessingMode.FAST)
        }
        audio.writeText("other") // Same length, different content.
        assertThrows(IOException::class.java) {
            JobCheckpoint.open(jobs, id, audio, manifest, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobCheckpoint.open(jobs, "../other", audio, manifest, null)
        }
    }

    @Test fun recoveredAsrKeepsVerifiedChunksAndRecomputesInvalidOnes() = runBlocking {
        val jobs = temp.newFolder()
        val audio = temp.newFile()
        val id = UUID.randomUUID().toString()
        val job = JobCheckpoint.open(jobs, id, audio, manifest, null)
        val original = File(job.directory, "original.wav")
        PcmWave.Writer(original).use { it.write(FloatArray(ChunkPlan.MaxSamples.toInt() + 1_600) { .15f }) }
        val inputHash = job.checkpointDecoded(original)
        val plan = ChunkPlan.create(original, job.directory)
        val (request, missing) = job.worker(WorkerStage.GIGAAM, original.name, inputHash, listOf("plan"), plan)
        assertNull(missing)
        val refs = plan.chunks.map { chunk ->
            val candidate = ImmutableAsrCandidate.create(chunk.id, AsrSource.GIGAAM, chunk.inputSha256, chunk.id,
                emptyList(), emptyList(), File(job.directory, "raw/gigaam/${chunk.id}.json"))
            CandidateRef(chunk.id, candidate.source.wireName, candidate.artifactFile.relativeTo(job.directory).path,
                candidate.artifactSha256)
        }
        JobFiles.save(WorkerStage.GIGAAM.requestFile(job.directory), request)
        val file = WorkerStage.GIGAAM.resultFile(job.directory)
        val done = WorkerResult(WorkerStage.GIGAAM, completed = true, requestId = request.requestId, candidates = refs)
        JobFiles.save(file, done)
        val reopened = JobCheckpoint.open(jobs, id, audio, manifest, null)
        assertEquals(inputHash, reopened.decodedHash())
        assertEquals(plan, reopened.chunks(original.name, inputHash))
        assertEquals(done, reopened.worker(WorkerStage.GIGAAM, original.name, inputHash, listOf("plan"), plan).second)

        File(job.directory, refs.last().file).appendText("corrupt")
        val (sameRequest, partial) = reopened.worker(WorkerStage.GIGAAM, original.name, inputHash, listOf("plan"), plan)
        assertEquals(request, sameRequest)
        assertFalse(requireNotNull(partial).completed)
        assertEquals(refs.take(1), partial.candidates)

        val (newRequest, stale) = reopened.worker(WorkerStage.GIGAAM, original.name, inputHash, listOf("changed plan"), plan)
        assertNotEquals(request.requestId, newRequest.requestId)
        assertNull(stale)
    }
}
