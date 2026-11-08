package com.vexto.engine.model

import com.vexto.engine.model.export.TranscriptExport
import org.junit.Assert.*
import org.junit.Test

class OverlapExportTest {
    @Test fun `overlap is optional internal metadata and never changes strict transcript`() {
        val base = Transcript(listOf(Utterance("s", 0, 4000, "Exact.")), listOf(Speaker("s")))
        for (value in listOf(true, false, null)) {
            val annotated = base.copy(utterances = base.utterances.map { it.copy(overlapDetected = value) })
            assertEquals(TranscriptExport.submissionJson(base), TranscriptExport.submissionJson(annotated))
            val metadata = TranscriptExport.jsonWithMetadata(annotated)
            assertEquals(value, TranscriptExport.parseJson(metadata).single().overlapDetected)
            assertEquals(value != null, metadata.contains("overlap_detected"))
        }
    }
}
