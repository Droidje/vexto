package com.vexto.engine.pipeline

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.Word
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.ImmutableAsrCandidate
import com.vexto.engine.review.LocalNativeExecutor
import com.vexto.engine.review.LocalNativeResult
import com.vexto.engine.review.sha256
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
            assertEquals("768", args[args.indexOf("-ac") + 1])
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

    @Test fun `reversed and negative native offsets remain auditable without rejecting complete ASR`() = runBlocking {
        val raw = """{"transcription":[{"text":" Один два три.","tokens":[
            {"text":"[_BEG_]","id":50000,"offsets":{"from":-1,"to":-1}},
            {"text":" Один","id":1,"offsets":{"from":240,"to":203}},
            {"text":" два","id":2,"offsets":{"from":-1,"to":300}},
            {"text":" три.","id":3,"offsets":{"from":500,"to":900}}]}]}"""
        val wav = temp.newFile("timing.wav").apply { writeText("synthetic WAV identity") }
        val chunk = ComparisonChunk("timing", wav, sha256(wav.readBytes()), 1000)
        val executor = LocalNativeExecutor { _, args, _, _, _, _ ->
            File(args[args.indexOf("-of") + 1] + ".json").writeText(raw)
            LocalNativeResult(10, 100)
        }
        val artifact = temp.root.resolve("timing/whisper.json")
        val candidate = WhisperComparisonProvider(executor, temp.newFile("model.bin")).generate(chunk, artifact)
        assertEquals(" Один два три.", candidate.text)
        assertEquals(listOf("[_BEG_]", " Один", " два", " три."), candidate.tokens.map { it.text })
        candidate.tokens.take(3).forEach { assertNull(it.startMs); assertNull(it.endMs) }
        assertEquals(listOf(Word("три.", 500, 900)), candidate.words)
        assertEquals(candidate.text, ImmutableAsrCandidate.load(artifact, candidate.artifactSha256).text)
        assertEquals(raw, artifact.parentFile.resolve("whisper.native.json").readText())
        val audit = Json.parseToJsonElement(artifact.parentFile.resolve("whisper.timing-audit.json").readText()).jsonObject
        assertEquals(sha256(raw.toByteArray()), audit.getValue("native_json_sha256").jsonPrimitive.content)
        val issues = audit.getValue("issues").jsonArray.map { it.jsonObject }
        assertEquals(listOf("negative_offsets", "reversed_offsets", "negative_offsets"),
            issues.map { it.getValue("reason").jsonPrimitive.content })
        assertEquals("240", issues[1].getValue("native_from").jsonPrimitive.content)
        assertEquals("203", issues[1].getValue("native_to").jsonPrimitive.content)
        val profile = Json.parseToJsonElement(artifact.parentFile.resolve("whisper.profile-audit.json").readText()).jsonObject
        assertEquals("768", profile.getValue("audio_context_frames").jsonPrimitive.content)
        assertEquals("15360", profile.getValue("audio_context_capacity_ms").jsonPrimitive.content)
        assertEquals("1000", profile.getValue("input_duration_ms").jsonPrimitive.content)
    }

    @Test fun `audio longer than ten seconds is recognized in successive windows`() = runBlocking {
        val wav = temp.newFile("twenty.wav")
        PcmWave.Writer(wav).use { it.write(FloatArray(16_000 * 20)) }
        val chunk = ComparisonChunk("long", wav, sha256(wav.readBytes()), 20_000)
        var calls = 0
        val executor = LocalNativeExecutor { _, args, _, _, _, _ ->
            calls++
            File(args[args.indexOf("-of") + 1] + ".json").writeText(nativeJson)
            LocalNativeResult(10, 100)
        }
        val candidate = WhisperComparisonProvider(executor, temp.newFile("model.bin"), audioContext = 512)
            .generate(chunk, temp.root.resolve("out/whisper.json"))
        assertEquals(2, calls)
        assertEquals(" Привет,  мир! Привет,  мир!", candidate.text)
        assertEquals(chunk.inputSha256, candidate.inputSha256)
        assertEquals(10_000L, candidate.words[2].startMs)
    }

    @Test fun `audio exceeding explicit context is rejected instead of silently truncated`() = runBlocking {
        val wav = temp.newFile("long.wav").apply { writeText("synthetic WAV identity") }
        var calls = 0
        val executor = LocalNativeExecutor { _, _, _, _, _, _ -> calls++; error("must not run") }
        val result = WhisperComparisonProvider(executor, temp.newFile("model.bin"), audioContext = 50).generateAll(
            listOf(ComparisonChunk("long", wav, sha256(wav.readBytes()), 1001)), temp.root.resolve("comparison"))
        assertEquals(0, calls)
        assertNull(result.single().candidate)
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
