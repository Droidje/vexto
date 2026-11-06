package com.vexto.engine.pipeline

import com.vexto.engine.model.Word
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.LocalNativeExecutor
import com.vexto.engine.review.LocalNativeResult
import com.vexto.engine.review.sha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WhisperComparisonProviderTest {
    @get:Rule val temp = TemporaryFolder()
    private val nativeJson = """{"transcription":[{"text":" Привет,  мир!","tokens":[
        {"text":"[_BEG_]","id":50000},
        {"text":" Прив","id":1,"offsets":{"from":0,"to":200}},
        {"text":"ет,","id":2,"offsets":{"from":200,"to":500}},
        {"text":"  мир!","id":3,"offsets":{"from":600,"to":950}}]}]}"""

    @Test fun `official full JSON preserves complete text tokens offsets and native word evidence`() {
        val result = WhisperComparisonProvider.parseNativeJson(nativeJson, 1000)
        assertEquals(" Привет,  мир!", result.text)
        assertEquals(4, result.tokens.size)
        assertEquals("[_BEG_]", result.tokens[0].text)
        assertNull(result.tokens[0].startMs)
        assertEquals(600L, requireNotNull(result.tokens[3].startMs))
        assertEquals(listOf(Word("Привет,", 0, 500), Word("мир!", 600, 950)), result.words)
        val unavailable = WhisperComparisonProvider.parseNativeJson(
            """{"transcription":[{"text":"слово","tokens":[{"text":"слово","id":1}]}]}""", 1000)
        assertEquals("слово", unavailable.text)
        assertTrue(unavailable.words.isEmpty())
    }

    @Test fun `serial comparison preserves original JSON and continues after one native failure`() = runBlocking {
        val calls = mutableListOf<String>()
        val chunks = listOf("one", "two", "three").map { id ->
            val file = temp.newFile("$id.wav").apply { writeText("synthetic input identity $id") }
            ComparisonChunk(id, file, sha256(file.readBytes()), 1000)
        }
        val executor = LocalNativeExecutor { executable, args, dir, _, _, _ ->
            assertEquals("libvexto_whisper_exec.so", executable)
            assertEquals("1", args[args.indexOf("-bs") + 1])
            assertEquals("1", args[args.indexOf("-bo") + 1])
            assertEquals("4", args[args.indexOf("-t") + 1])
            assertTrue(args.contains("-fa")); assertTrue(args.contains("-ng"))
            calls += dir.name
            if (dir.name == "two") throw IOException("simulated native failure")
            File(args[args.indexOf("-of") + 1] + ".json").writeText(nativeJson)
            LocalNativeResult(10, 100)
        }
        val result = WhisperComparisonProvider(executor, temp.newFile("model.bin")).generateAll(chunks,
            temp.root.resolve("comparison"))
        assertEquals(listOf("one", "two", "three"), calls)
        assertEquals(3, result.size)
        assertEquals(AsrSource.WHISPER, result[0].candidate!!.source)
        assertNull(result[1].candidate)
        assertEquals("whisper_candidate_failed", result[1].failureCode)
        assertNotNull(result[2].candidate)
        assertEquals(nativeJson, temp.root.resolve("comparison/one/whisper.native.json").readText())
        assertEquals(chunks[0].inputSha256, result[0].candidate!!.inputSha256)
    }

    @Test fun `changed input hash is rejected before any native execution`() = runBlocking {
        val file = temp.newFile("chunk.wav").apply { writeText("synthetic") }
        var calls = 0
        val executor = LocalNativeExecutor { _, _, _, _, _, _ -> calls++; error("must not run") }
        val results = WhisperComparisonProvider(executor, temp.newFile("model.bin")).generateAll(
            listOf(ComparisonChunk("one", file, "a".repeat(64), 1000)), temp.root.resolve("comparison"))
        assertEquals(0, calls)
        assertNull(results.single().candidate)
    }
}
