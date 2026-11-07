package com.vexto.engine.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingModeTest {
    @Test fun automaticThresholdAndExplicitChoices() {
        assertEquals(ProcessingMode.AUTO, TranscriptionOptions().processingMode)
        assertEquals(ProcessingMode.QUALITY, ProcessingMode.AUTO.resolve(300_000))
        assertEquals(ProcessingMode.FAST, ProcessingMode.AUTO.resolve(300_001))
        assertEquals(ProcessingMode.FAST, ProcessingMode.FAST.resolve(1))
        assertEquals(ProcessingMode.QUALITY, ProcessingMode.QUALITY.resolve(1_800_000))
    }
}
