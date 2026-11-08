package com.vexto.engine.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class QwenAsrSelectorTest {
    @get:Rule val temp = TemporaryFolder()
    private fun pair(id: String, text: String = "Тестовая реплика."): AsrCandidatePair {
        val dir = temp.root.resolve(id).apply { mkdirs() }
        fun candidate(source: AsrSource) = ImmutableAsrCandidate.create(id, source, "a".repeat(64), if (source == AsrSource.GIGAAM) text else "$text Другой вариант.",
            emptyList(), emptyList(), dir.resolve(source.wireName + ".json"))
        return AsrCandidatePair(id, "a".repeat(64), candidate(AsrSource.GIGAAM), candidate(AsrSource.WHISPER))
    }

    @Test fun identicalTextsBypassTheModelWithoutClaimingAnLlmDecision() = runBlocking {
        val base = pair("same")
        val whisper = ImmutableAsrCandidate.create(base.chunkId, AsrSource.WHISPER, base.inputSha256,
            base.gigaam!!.text, emptyList(), emptyList(), temp.root.resolve("equal-whisper.json"))
        val noModel = LocalNativeExecutor { _, _, _, _, _, _ -> error("LLM must not run") }
        val result = QwenAsrSelector(noModel, temp.newFile("equal-model.gguf"))
            .selectAll(listOf(base.copy(whisper = whisper)), temp.root.resolve("equal-selection")).single()
        assertEquals(AsrSelectionStatus.EQUIVALENT, result.status)
        assertFalse(result.audit.selectedByLlm)
        assertEquals(base.gigaam!!.text, result.displayCandidate.text)
    }

    @Test fun `every chunk receives its own local tool call and failures do not cut the review list`() = runBlocking {
        val pairs = listOf(pair("one"), pair("two"), pair("three"))
        val calls = mutableListOf<String>()
        val model = temp.newFile("model.gguf")
        val executor = LocalNativeExecutor { executable, args, dir, stdout, _, _ ->
            assertEquals("libvexto_llama_exec.so", executable)
            assertEquals("1024", args[args.indexOf("-c") + 1])
            assertTrue(args.contains("--no-repack"))
            assertTrue(args.contains("--no-warmup"))
            assertTrue(args.contains("--no-context-shift"))
            calls += dir.name
            if (dir.name == "two") throw IOException("simulated native failure")
            stdout.writeText(""""whisper"""")
            LocalNativeResult(10, 100)
        }
        val results = QwenAsrSelector(executor, model).selectAll(pairs, temp.root.resolve("selection"))
        assertEquals(listOf("one", "two", "three"), calls)
        assertEquals(3, results.size)
        assertEquals(listOf(AsrSelectionStatus.SELECTED_BY_LLM, AsrSelectionStatus.UNRESOLVED,
            AsrSelectionStatus.SELECTED_BY_LLM), results.map { it.status })
        assertFalse(results[1].audit.selectedByLlm)
        for (index in listOf(0, 2)) {
            val chunkId = pairs[index].chunkId
            val bound = temp.root.resolve("selection/$chunkId/bound-tool-call.json").readBytes()
            val fields = Json.parseToJsonElement(bound.toString(Charsets.UTF_8)).jsonObject
            assertEquals(chunkId, fields.getValue("chunk_id").jsonPrimitive.content)
            assertEquals("whisper", fields.getValue("source").jsonPrimitive.content)
            assertEquals(ScopedSourceResponseKind, results[index].audit.responseKind)
            assertEquals(sha256(bound), results[index].audit.scopedToolCallSha256)
        }
        assertFalse(temp.root.resolve("selection/two/bound-tool-call.json").exists())
        pairs.forEach { it.gigaam!!.verifiedBytes(); it.whisper!!.verifiedBytes() }
    }

    @Test fun `oversize never truncates candidates and sole source bypasses generation`() = runBlocking {
        val oversized = pair("large", "Ж".repeat(5000))
        val original = oversized.whisper!!.verifiedBytes()
        val only = pair("only").copy(whisper = null)
        val executor = LocalNativeExecutor { _, _, _, _, _, _ -> error("native must not run") }
        val results = QwenAsrSelector(executor, temp.newFile("model.gguf")).selectAll(
            listOf(oversized, only), temp.root.resolve("selection"))
        assertEquals(AsrSelectionStatus.UNRESOLVED, results[0].status)
        assertEquals("prompt_exceeds_context_budget", results[0].audit.reason)
        assertArrayEquals(original, oversized.whisper!!.verifiedBytes())
        assertEquals(AsrSelectionStatus.ONE_AVAILABLE, results[1].status)
        assertFalse(results[1].audit.selectedByLlm)
    }

    @Test fun nativeTokenRejectionDoesNotPreventReviewingTheNextWholeRussianCandidate() = runBlocking {
        val tooManyTokens = pair("token_reject", "Ж".repeat(1000))
        val text = "Сегодня мы подробно обсуждаем обработку русской речи и сохранение всех оригиналов. ".repeat(6)
        val full = pair("whole", text)
        val calls = mutableListOf<String>()
        val executor = LocalNativeExecutor { _, args, dir, stdout, _, _ ->
            calls += dir.name
            if (dir.name == "token_reject") throw NativeRequestRejectedException(NativeRequestRejectedException.ContextBudget)
            val prompt = java.io.File(args[args.indexOf("-f") + 1]).readText()
            assertTrue(prompt.toByteArray().size > 864)
            val data = Json.parseToJsonElement(prompt.substringAfter('\n')).jsonObject
            assertEquals(full.gigaam!!.text, data.getValue("gigaam").jsonPrimitive.content)
            assertEquals(full.whisper!!.text, data.getValue("whisper").jsonPrimitive.content)
            assertEquals("1024", args[args.indexOf("-c") + 1])
            stdout.writeText("\"whisper\"")
            LocalNativeResult(10, 100)
        }
        val selected = QwenAsrSelector(executor, temp.newFile("native-token-model.gguf")).selectAll(
            listOf(tooManyTokens, full), temp.root.resolve("native-token-selection"))
        assertEquals(listOf("token_reject", "whole"), calls)
        assertEquals("prompt_exceeds_context_budget", selected[0].audit.reason)
        assertEquals(AsrSelectionStatus.SELECTED_BY_LLM, selected[1].status)
        assertEquals(full.whisper!!.text, selected[1].displayCandidate.text)
        listOf(tooManyTokens, full).forEach { it.gigaam!!.verifiedBytes(); it.whisper!!.verifiedBytes() }
    }

    @Test fun `tool schema has one chunk enum and no reasoning in generation prefix`() {
        val request = SelectorPrompt().render(pair("one"))
        assertTrue(request.schema.contains("\"additionalProperties\":false"))
        assertTrue(request.schema.contains("\"chunk_id\":{\"type\":\"string\",\"enum\":[\"one\"]}"))
        assertTrue(request.prompt.contains("gigaam"))
        assertTrue(request.prompt.contains("whisper"))
        assertFalse(request.prompt.contains("Prefer gigaam"))
        assertTrue(SelectorPrompt.NonThinkingTemplate.contains("</think>"))
    }

    @Test fun `source presentation and enum order alternate while complete texts remain equal`() {
        val firstPair = pair("chunk_000000", "Текст с \"кавычками\" и новой\nстрокой.")
        val secondPair = pair("chunk_000001", firstPair.gigaam!!.text)
        fun request(pair: AsrCandidatePair) = SelectorPrompt().render(pair)
        val first = request(firstPair)
        val second = request(secondPair)
        fun data(request: SelectorRequest) = Json.parseToJsonElement(request.prompt.substringAfter('\n')).jsonObject
        fun sources(request: SelectorRequest) = data(request).keys.filter { it != "chunk_id" }
        assertEquals(sources(first).reversed(), sources(second))
        for ((pair, request) in listOf(firstPair to first, secondPair to second)) {
            assertTrue(request.prompt.contains("Both sources have equal priority"))
            assertEquals(pair.gigaam!!.text, data(request).getValue("gigaam").jsonPrimitive.content)
            assertEquals(pair.whisper!!.text, data(request).getValue("whisper").jsonPrimitive.content)
            val enum = Json.parseToJsonElement(request.schema).jsonObject.getValue("properties").jsonObject
                .getValue("source").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(sources(request), enum)
        }
    }

    @Test(expected = CancellationException::class)
    fun `user cancellation is not converted to an unresolved success`() = runBlocking {
        val executor = LocalNativeExecutor { _, _, _, _, _, _ -> throw CancellationException("cancelled") }
        QwenAsrSelector(executor, temp.newFile("model.gguf")).selectAll(listOf(pair("one")), temp.root.resolve("selection"))
        Unit
    }
}
