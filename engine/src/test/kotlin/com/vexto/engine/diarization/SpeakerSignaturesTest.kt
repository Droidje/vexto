package com.vexto.engine.diarization

import com.vexto.engine.model.SpeakerTurn
import com.vexto.engine.model.TranscriptAssembler
import com.vexto.engine.model.Word
import org.junit.Assert.*
import org.junit.Test

class SpeakerSignaturesTest {
    @Test fun `centroids use final labels and are normalized`() {
        val turns = listOf(SpeakerTurn(7, 0, 3000), SpeakerTurn(2, 3000, 6000))
        val voices = SpeakerSignatures.build("model-A", listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, 1f)),
            floatArrayOf(3f, 1f, 2f), intArrayOf(7, 7, 2), turns)
        assertEquals(2, voices.size)
        assertEquals(3000L, voices.getValue(7).speechMs) // actual turns, not overlapping window weights
        assertEquals(2, voices.getValue(7).windowCount)
        assertEquals(0.9486833f, voices.getValue(7).values[0], 1e-6f)
        val transcript = TranscriptAssembler().assemble(listOf(Word("первый", 0, 1000), Word("второй", 3000, 4000)), turns, voices)
        assertEquals("speaker_1", transcript.speakers[0].id)
        assertSame(voices[7], transcript.speakers[0].voice)
        assertSame(voices[2], transcript.speakers[1].voice)
        assertFalse(com.vexto.engine.model.export.TranscriptExport.json(transcript).contains("model-A"))
    }

    @Test fun `empty and opposing signatures are not retained`() {
        assertTrue(SpeakerSignatures.build("m", emptyList(), floatArrayOf(), intArrayOf(), emptyList()).isEmpty())
        assertTrue(SpeakerSignatures.build("m", listOf(floatArrayOf(1f), floatArrayOf(-1f)), floatArrayOf(1f,1f),
            intArrayOf(0,0), listOf(SpeakerTurn(0,0,1000))).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid vector never becomes a profile`() {
        SpeakerSignatures.build("m", listOf(floatArrayOf(Float.NaN)), floatArrayOf(1f), intArrayOf(0), listOf(SpeakerTurn(0,0,1000)))
    }
}
