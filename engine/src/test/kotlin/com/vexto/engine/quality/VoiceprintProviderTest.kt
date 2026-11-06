package com.vexto.engine.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceprintProviderTest {
    @Test fun excludesOtherSpeakersButKeepsSameLabelDuplicates() {
        val turns = listOf(PipelineTurn(0, 0, 3000), PipelineTurn(0, 0, 3000), PipelineTurn(1, 1000, 2000))
        assertEquals(listOf(PipelineTurn(0, 0, 1000), PipelineTurn(0, 2000, 3000)),
            VoiceprintProvider.cleanTurns(turns, 3000))
    }
    @Test fun retainsAcousticIdsAndRejectsInvalidBounds() {
        assertEquals(listOf(PipelineTurn(7, 0, 500), PipelineTurn(2, 500, 900)),
            VoiceprintProvider.cleanTurns(listOf(PipelineTurn(7, 0, 500), PipelineTurn(2, 500, 900)), 900))
        assertThrows(IllegalArgumentException::class.java) {
            VoiceprintProvider.cleanTurns(listOf(PipelineTurn(0, 0, 1000)), 999)
        }
        assertEquals(emptyList<PipelineTurn>(), VoiceprintProvider.cleanTurns(emptyList(), 0))
    }
}
