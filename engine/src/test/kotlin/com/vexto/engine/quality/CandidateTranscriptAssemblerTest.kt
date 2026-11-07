package com.vexto.engine.quality

import com.vexto.engine.model.Word
import com.vexto.engine.model.export.TranscriptExport
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.ImmutableAsrCandidate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CandidateTranscriptAssemblerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hash = "a".repeat(64)
    private val id = "chunk_000000"
    private val plan = ChunkPlan(32000, listOf(AudioChunk(id, 0, 32000, "chunk.wav", hash)))
    private fun candidate(text: String, words: List<Word> = emptyList(), chunkId: String = id): ImmutableAsrCandidate =
        ImmutableAsrCandidate.create(chunkId, AsrSource.GIGAAM, hash, text, emptyList(), words,
            File(temporary.newFolder(), "raw.json"))

    @Test fun missingTimesAndUnicodeWhitespaceKeepEveryCharacter() {
        val text = "  Первое\tвторое\nтретье\u00a0четвёртое\u0085пятое.\r\n"
        val raw = candidate(text)
        val bytes = raw.artifactFile.readBytes()
        val result = CandidateTranscriptAssembler().assemble(plan, mapOf(id to raw), listOf(PipelineTurn(0, 0, 2000)))
        assertEquals(text, result.transcript.utterances.joinToString("") { it.text })
        val words = result.transcript.utterances.flatMap { it.words }
        assertEquals(5, words.size)
        assertTrue(words.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= 2000 })
        assertTrue(words.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        assertEquals(listOf(id), result.estimatedTimingChunks)
        assertEquals(text, TranscriptExport.parseJson(TranscriptExport.json(result.transcript)).single().text)
        assertArrayEquals(bytes, raw.artifactFile.readBytes())
    }

    @Test fun decreasingWordTimesFallBackWithoutReorderingWords() {
        val text = "первое второе"
        val raw = candidate(text, listOf(Word("первое", 1500, 1800), Word("второе", 100, 300)))
        val result = CandidateTranscriptAssembler().assemble(plan, mapOf(id to raw),
            listOf(PipelineTurn(0, 0, 1000), PipelineTurn(1, 1000, 2000)))
        assertEquals(text, result.transcript.utterances.joinToString("") { it.text })
        assertEquals(listOf("первое", "второе"), result.transcript.utterances.flatMap { it.words }.map { it.text })
        assertEquals(listOf(id), result.estimatedTimingChunks)
        val rows = TranscriptExport.parseJson(TranscriptExport.json(result.transcript))
        assertTrue(rows.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        assertEquals(1500L, raw.words.first().startMs)
    }

    @Test fun boundaryRepairsAreMarkedEstimatedAndKeepUsefulStarts() {
        val raw = candidate("одно два", listOf(Word("одно", 0, 0), Word("два", 1500, 9000)))
        val result = CandidateTranscriptAssembler().assemble(plan, mapOf(id to raw), emptyList())
        val words = result.transcript.utterances.single().words
        assertEquals(listOf(0L, 1500L), words.map { it.startMs })
        assertEquals(listOf(1L, 2000L), words.map { it.endMs })
        assertEquals("speaker_unknown", result.transcript.utterances.single().speakerId)
        assertEquals(listOf(id), result.estimatedTimingChunks)
        assertEquals(9000L, raw.words.last().endMs)
    }

    @Test fun overlappingSpeakerEvidenceDoesNotDuplicateOrDropRawText() {
        val text = " Да,\tпродолжай.\nдальше  "
        val raw = candidate(text, listOf(Word("Да,", 0, 600), Word("продолжай.", 700, 1200), Word("дальше", 1700, 1900)))
        val result = CandidateTranscriptAssembler().assemble(plan, mapOf(id to raw),
            listOf(PipelineTurn(0, 0, 700), PipelineTurn(1, 500, 1600)))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_unknown"), result.transcript.utterances.map { it.speakerId })
        assertEquals(text, result.transcript.utterances.joinToString("") { it.text })
        assertTrue(result.estimatedTimingChunks.isEmpty())
    }

    @Test fun anotherChunkOrChangedOriginalIsRejectedAtAssemblyBoundary() {
        val foreign = candidate("чужой текст", chunkId = "chunk_000001")
        assertThrows(IllegalStateException::class.java) {
            CandidateTranscriptAssembler().assemble(plan, mapOf(id to foreign), emptyList())
        }
        val changed = candidate("исходный текст")
        changed.artifactFile.writeText("changed after selection")
        assertThrows(IllegalStateException::class.java) {
            CandidateTranscriptAssembler().assemble(plan, mapOf(id to changed), emptyList())
        }
    }
}
