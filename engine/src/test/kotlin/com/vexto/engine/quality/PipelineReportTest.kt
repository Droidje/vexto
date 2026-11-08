package com.vexto.engine.quality

import org.junit.Assert.*
import org.junit.Test

class PipelineReportTest {
    @Test fun exactAgreementDoesNotPretendQwenWasInvoked() {
        val equal = PipelineCoverage(2,2,2,2,0,0,2)
        assertTrue(equal.selectionResolved)
        assertFalse(equal.fullProfileCompleted(true,emptyList(),true))
        assertTrue(equal.fullProfileCompleted(true,emptyList(),true,localLlmUsed=true))
    }
    @Test fun allStagesIncludingConfirmedLlmAreRequiredForFullProfile() {
        val all = PipelineCoverage(4,4,4,4,4,0)
        assertTrue(all.complete)
        assertTrue(all.selectionResolved)
        assertTrue(all.fullProfileCompleted(true,emptyList(),true))
        assertFalse(all.fullProfileCompleted(false,emptyList(),true))
        assertFalse(all.fullProfileCompleted(true,emptyList(),false))
        assertFalse(all.fullProfileCompleted(true,listOf("EMOTION_FAILED:one_line"),true))
    }
    @Test fun missingChunkNeverCountsAsResolvedSelection() {
        val missing = PipelineCoverage(4,3,3,3,3,0)
        assertFalse(missing.complete)
        assertFalse(missing.selectionResolved)
        assertFalse(missing.fullProfileCompleted(true,emptyList(),true))
    }
    @Test fun technicalFallbackKeepsCoverageWithoutPretendingLlmSucceeded() {
        val fallback=PipelineCoverage(4,4,4,4,3,1)
        assertTrue(fallback.complete)
        assertFalse(fallback.selectionResolved)
        assertFalse(fallback.fullProfileCompleted(true,emptyList(),true))
    }
    @Test fun singleRecognizerCannotSatisfyFullProfileOrLocalLlmRequirement() {
        val one=PipelineCoverage(4,4,0,4,0,0)
        assertTrue(one.complete)
        assertTrue(one.selectionResolved)
        assertFalse(one.fullProfileCompleted(true,emptyList(),true))
    }
    @Test fun whisperOnlyRouteStaysCompleteWithoutTheFullProfile() {
        val foreign = PipelineCoverage(4, 0, 4, 4, 0, 0)
        assertTrue(foreign.complete)
        assertTrue(foreign.selectionResolved)
        assertFalse(foreign.fullProfileCompleted(false, listOf("ASR_ROUTE:whisper_only:en"), true))
    }
    @Test fun emptyInputDoesNotClaimTheLocalLlmWasUsed() {
        assertFalse(PipelineCoverage(0,0,0,0,0,0).fullProfileCompleted(true,emptyList(),true))
    }
}
