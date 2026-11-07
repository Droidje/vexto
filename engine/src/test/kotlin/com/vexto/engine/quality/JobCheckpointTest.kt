package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.models.QualityManifest
import com.vexto.engine.model.ProcessingStats
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.export.TranscriptExport
import com.vexto.engine.review.QwenAsrSelector
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

    @Test fun explicitRetryDoesNotReuseUnresolvedFailedOrLegacyFinishedSnapshots() {
        val job = JobCheckpoint.open(temp.newFolder(), UUID.randomUUID().toString(), temp.newFile(), manifest, null)
        val transcript = Transcript.Empty
        val export = File(job.directory, "transcript.json").apply { writeText(TranscriptExport.submissionJson(transcript)) }
        val metadata = File(job.directory, "transcript.metadata.json").apply { writeText(TranscriptExport.jsonWithMetadata(transcript)) }
        val stats = ProcessingStats(0, 0, emptyMap(), 0, "test", 2, artifactDirectory = job.directory.absolutePath,
            fullProfileCompleted = false)
        val report = PipelineReport(audioMs = 0, processingMs = 0, modelProfile = "test",
            coverage = PipelineCoverage(0, 0, 0, 0, 0, 0), complete = true, fullProfileCompleted = false,
            selectionResolved = true, peakRssBytes = 0, reserveBytes = 0, budgetBytes = 1,
            stageMs = emptyMap(), stages = listOf(WorkerResult(WorkerStage.SELECT, completed = true)),
            transcriptSha256 = JobFiles.sha256(export), metadataSha256 = JobFiles.sha256(metadata), warnings = emptyList())
        val reportFile = File(job.directory, "completion.json")
        JobFiles.save(reportFile, report)
        job.saveFinished(transcript, stats)
        assertNotNull(job.finished()) // fullProfileCompleted is not required.
        assertNull(job.finished("new-overlap-model"))
        job.saveFinished(transcript, stats, "new-overlap-model")
        assertNotNull(job.finished("new-overlap-model"))
        assertNull(job.finished()) // Removing the optional model also invalidates prior flags.
        job.saveFinished(transcript, stats)
        JobFiles.save(reportFile, report.copy(warnings = listOf("OVERLAP_UNAVAILABLE:model_pack:IllegalArgumentException")))
        assertNull(job.finished()) // Explicit retry must revisit optional failures, without invalidating ASR.
        JobFiles.save(reportFile, report)
        job.saveFinished(transcript, stats.copy(selectionResolved = false))
        assertNull(job.finished())
        job.saveFinished(transcript, stats)
        JobFiles.save(reportFile, report.copy(stages = listOf(WorkerResult(WorkerStage.SELECT, error = "native failure"))))
        assertNull(job.finished())
        JobFiles.save(reportFile, report)
        JobFiles.save(File(job.directory, "finished.checkpoint.json"), JobCheckpoint.Finished(transcript, stats))
        assertNull(job.finished())
        job.saveFinished(transcript, stats)
        assertEquals(QwenAsrSelector.Version, requireNotNull(job.finished()).selectorVersion)
    }

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
