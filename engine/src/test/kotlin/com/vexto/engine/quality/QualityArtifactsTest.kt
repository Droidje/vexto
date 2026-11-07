package com.vexto.engine.quality

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.Word
import com.vexto.engine.review.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class QualityArtifactsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun chunksCoverAllSamplesExactlyOnceAndShareExactPcm() = runBlocking {
        val root = temporary.newFolder()
        val source = File(root, "source.wav")
        val samples = FloatArray(2 * ChunkPlan.MaxSamples.toInt() + 7) { ((it % 1500) - 750) / 32768f }
        PcmWave.Writer(source).use { it.write(samples) }
        val original = source.readBytes()
        val plan = ChunkPlan.create(source, root)
        plan.validate()
        assertEquals(samples.size.toLong(), plan.sampleCount)
        assertTrue(plan.chunks.size >= 3)
        val waves = ChunkWaveMaterializer(root, plan, source) { fail(it) }
        assertTrue(plan.chunks.none { JobFiles.inside(root, it.wav).exists() })
        val joined = plan.chunks.flatMap { chunk ->
            val wav = JobFiles.inside(root, chunk.wav)
            val pcm = waves.withChunk(chunk) { current ->
                assertEquals(wav, current.wav)
                assertTrue(current.wav.isFile)
                assertEquals(chunk.inputSha256, current.inputSha256)
                assertEquals(chunk.inputSha256, JobFiles.sha256(current.wav))
                assertEquals(chunk.durationMs, current.durationMs)
                current.wav.readBytes().drop(44)
            }
            assertFalse(wav.exists())
            pcm
        }.toByteArray()
        assertArrayEquals(original.copyOfRange(44, original.size), joined)
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun invalidOrTruncatedWavIsRejected() {
        val file = temporary.newFile()
        PcmWave.Writer(file).use { it.padSilence(160) }
        RandomAccessFile(file, "rw").use { it.setLength(45) }
        assertThrows(IllegalArgumentException::class.java) { PcmWave.inspect(file) }
    }
    @Test fun exactSelectedTextSurvivesSpeakerSplitsAndOutsideTurnsIsUnknown() {
        val root = temporary.newFolder()
        val text = "  Привет, мир!\nСнова да.  "
        val candidate = ImmutableAsrCandidate.create("chunk_000000", AsrSource.WHISPER, "a".repeat(64), text,
            emptyList(), listOf(Word("Привет,",0,500), Word("мир!",500,1000), Word("Снова",1000,1500), Word("да.",1500,2000)),File(root,"raw.json"))
        val plan = ChunkPlan(32000, listOf(AudioChunk("chunk_000000",0,32000,"chunk.wav","a".repeat(64))))
        val result = CandidateTranscriptAssembler().assemble(plan,mapOf("chunk_000000" to candidate),
            listOf(PipelineTurn(0,0,500),PipelineTurn(1,500,1000),PipelineTurn(0,1000,1500)))
        assertEquals(text,result.transcript.utterances.joinToString("") { it.text })
        assertEquals("speaker_unknown",result.transcript.utterances.last().speakerId)
        assertTrue(result.estimatedTimingChunks.isEmpty())
        assertEquals(text,ImmutableAsrCandidate.load(candidate.artifactFile,candidate.artifactSha256).text)
    }
    @Test fun sameWordCountWithDifferentWordsDoesNotBorrowFalseTimings() {
        val root=temporary.newFolder()
        val c=ImmutableAsrCandidate.create("chunk_000000",AsrSource.GIGAAM,"b".repeat(64),"два слова",emptyList(),
            listOf(Word("другой",0,100),Word("текст",100,200)),File(root,"raw.json"))
        val plan=ChunkPlan(32000,listOf(AudioChunk(c.chunkId,0,32000,"x.wav",c.inputSha256)))
        val result=CandidateTranscriptAssembler().assemble(plan,mapOf(c.chunkId to c),emptyList())
        assertEquals(listOf(c.chunkId),result.estimatedTimingChunks)
        assertEquals("два слова",result.transcript.utterances.single().text)
    }
    @Test fun fragmentedDuplicateTurnsDoNotInflateSpeakerEvidence() {
        val assembler=CandidateTranscriptAssembler()
        val turns=listOf(PipelineTurn(0,0,100),PipelineTurn(0,0,100),PipelineTurn(1,0,150))
        assertEquals(1,assembler.label(Word("x",0,200),turns))
    }
    @Test fun pathTraversalCannotEscapePrivateArtifacts() {
        val root=temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) { JobFiles.inside(root,"../outside") }
    }
    @Test fun meetingStageDeadlineCoversAllChunksRatherThanFixedThirtyMinutes() {
        assertTrue(StageTimeoutPolicy.milliseconds(WorkerStage.WHISPER,1_200_000,120) > 30*60*1000L)
        assertTrue(StageTimeoutPolicy.milliseconds(WorkerStage.SELECT,1_200_000,120) >= 120*120_000L)
    }
}
