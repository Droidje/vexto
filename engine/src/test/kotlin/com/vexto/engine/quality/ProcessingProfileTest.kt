package com.vexto.engine.quality

import com.vexto.engine.model.ProcessingMode
import com.vexto.engine.models.QualityManifest
import org.junit.Assert.*
import org.junit.Test

class ProcessingProfileTest {
    @Test fun fastDefersEmotionWeightsAndKeepsVoiceprintsDisabled() {
        val fast = ProcessingProfile.resolve(ProcessingMode.AUTO, 300_001)
        assertEquals(setOf(WorkerStage.DIARIZE, WorkerStage.GIGAAM, WorkerStage.WHISPER, WorkerStage.SELECT, WorkerStage.EMOTION),
            fast.expectedStages(slavic = true))
        assertEquals(setOf(WorkerStage.DIARIZE, WorkerStage.WHISPER, WorkerStage.SELECT, WorkerStage.EMOTION), fast.expectedStages(slavic = false))
        assertFalse(fast.voiceprints)
        assertTrue(fast.backgroundEmotions)
        assertTrue("qwen" in QualityManifest.FastRequired)
        assertTrue(QualityManifest.FastRequired.intersect(setOf("gtcrn", "emotion", "voiceprint_embedding")).isEmpty())

        val quality = ProcessingProfile.resolve(ProcessingMode.AUTO, 300_000)
        assertTrue(quality.voiceprints)
        assertEquals(WorkerStage.entries.toSet() - setOf(WorkerStage.GIGAAM_REFINE, WorkerStage.WHISPER_REFINE), quality.expectedStages(slavic = true))
    }
}
