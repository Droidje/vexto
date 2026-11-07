package com.vexto.engine.quality

import com.vexto.engine.model.Speaker
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.Utterance
import com.vexto.engine.model.Word
import org.junit.Assert.assertEquals
import org.junit.Test

class AcousticTurnTranscriptTest {
    @Test fun overlappingTurnsKeepBothIntervalsAndEachWordOnce() {
        val transcript = Transcript(
            listOf(Utterance("speaker_1", 40, 1800, "один два три",
                listOf(Word("один", 40, 400), Word("два", 500, 900), Word("три", 1200, 1800)))),
            listOf(Speaker("speaker_1")),
        )
        val placed = AcousticTurnTranscript.place(transcript, listOf(
            PipelineTurn(0, 0, 1000),
            PipelineTurn(1, 800, 2000),
        ))
        assertEquals(listOf(0L to 1000L, 800L to 2000L), placed.utterances.map { it.startMs to it.endMs })
        assertEquals(listOf("speaker_1", "speaker_2"), placed.utterances.map { it.speakerId })
        assertEquals(listOf("один", "два", "три"), placed.utterances.flatMap { it.words }.map { it.text })
        assertEquals(listOf("один два", "три"), placed.utterances.map { it.text })
    }

    @Test fun aTurnWithoutWordsStaysInTheExport() {
        val transcript = Transcript(
            listOf(Utterance("speaker_1", 100, 400, "да", listOf(Word("да", 100, 400)))),
            listOf(Speaker("speaker_1")),
        )
        val placed = AcousticTurnTranscript.place(transcript, listOf(
            PipelineTurn(0, 0, 500),
            PipelineTurn(1, 2000, 2500),
        ))
        assertEquals(listOf(0L to 500L, 2000L to 2500L), placed.utterances.map { it.startMs to it.endMs })
        assertEquals(listOf("да", ""), placed.utterances.map { it.text })
    }

    @Test fun changedWordSequenceFallsBackToTheOriginalTranscript() {
        val transcript = Transcript(
            listOf(Utterance("speaker_1", 0, 1000, "видно только здесь", emptyList())),
            listOf(Speaker("speaker_1")),
        )
        val placed = AcousticTurnTranscript.place(transcript, listOf(PipelineTurn(0, 0, 1000)))
        assertEquals(transcript, placed)
    }
}
