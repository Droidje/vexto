package com.vexto.engine.quality

import com.vexto.engine.models.OverlapManifest
import com.vexto.engine.models.QualityManifest
import com.vexto.engine.pipeline.UtteranceOverlapEvidence
import com.vexto.engine.pipeline.UtteranceOverlapTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class OverlapCheckpointTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `resume requires exact model target geometry and complete cardinality`() {
        val job = JobCheckpoint.open(temp.newFolder(), UUID.randomUUID().toString(), temp.newFile(),
            QualityManifest("test", emptyMap()), null)
        val manifest = OverlapManifest("v1", "overlap/wavlm.onnx", "a".repeat(64), 10,
            OverlapManifest.Threshold, 64000, 16000)
        val targets = listOf(UtteranceOverlapTarget(0, "s", 0, 4000), UtteranceOverlapTarget(1, "s", 4000, 4500))
        JobFiles.save(File(job.directory, "overlap-model.json"), manifest)
        JobFiles.save(File(job.directory, "overlap-targets.json"), targets)
        val key = listOf(manifest.revision, "target-digest")
        val request = job.worker(WorkerStage.OVERLAP, "original.wav", "audio", key, null).first
        JobFiles.save(WorkerStage.OVERLAP.requestFile(job.directory), request)
        val evidence = listOf(UtteranceOverlapEvidence(targets[0], .95, 1, manifest.revision),
            UtteranceOverlapEvidence(targets[1], null, 0, manifest.revision))
        val done = WorkerResult(WorkerStage.OVERLAP, completed = true, requestId = request.requestId, overlaps = evidence)
        val resultFile = WorkerStage.OVERLAP.resultFile(job.directory)
        fun resume() = job.worker(WorkerStage.OVERLAP, "original.wav", "audio", key, null).second!!
        JobFiles.save(resultFile, done)
        assertTrue(resume().completed)
        assertNull(job.worker(WorkerStage.OVERLAP, "original.wav", "audio", listOf("new-model"), null).second)
        JobFiles.save(resultFile, done.copy(overlaps = evidence.take(1)))
        assertFalse(resume().completed)
        JobFiles.save(resultFile, done.copy(overlaps = listOf(evidence[0], evidence[0], evidence[1])))
        assertFalse(resume().completed)
        JobFiles.save(resultFile, done.copy(overlaps = listOf(evidence[0].copy(modelRevision = "stale"), evidence[1])))
        assertEquals(listOf(evidence[1]), resume().overlaps)
        JobFiles.save(resultFile, done.copy(overlaps = listOf(evidence[0].copy(target = targets[0].copy(speakerId = "other")), evidence[1])))
        assertEquals(listOf(evidence[1]), resume().overlaps)
    }

    @Test fun `manifest fixes calibration geometry and rejects unsafe paths`() {
        val manifest = OverlapManifest("v1", "overlap/wavlm.onnx", "a".repeat(64), 10,
            OverlapManifest.Threshold, 64000, 16000)
        manifest.validate()
        for (bad in listOf(manifest.copy(path = "../model.onnx"), manifest.copy(threshold = .5),
            manifest.copy(windowSamples = 32000), manifest.copy(sampleRate = 8000), manifest.copy(sha256 = "bad"))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        }
    }
}
