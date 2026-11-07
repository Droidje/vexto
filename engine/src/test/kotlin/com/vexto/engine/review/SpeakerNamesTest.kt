package com.vexto.engine.review

import com.vexto.engine.model.*
import com.vexto.engine.model.export.TranscriptExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeakerNamesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun dialogue(vararg rows: String) = Transcript(rows.mapIndexed { i, text ->
        Utterance("speaker_${i + 1}", i * 1000L, i * 1000L + 900, text)
    }, rows.indices.map { Speaker("speaker_${it + 1}") })

    @Test fun `one warm bounded call supplies names without changing text time or submission JSON`() = runBlocking {
        val source = dialogue("Меня зовут Анна.", "Меня зовут Борис.")
        val model = temporary.newFile("qwen.gguf")
        var calls = 0
        val executor = LocalNativeExecutor { _, args, dir, stdout, _, timeout ->
            calls++
            assertEquals(10_000L, timeout)
            val ordinaryArgs = BoundedQwenRequest.arguments(model, SelectorRequest("data", "{}"), temporary.root.resolve("asr"))
            fun stable(values: List<String>) = values.chunked(1).flatten().filterNot {
                it.startsWith(temporary.root.path)
            }
            assertEquals(stable(ordinaryArgs), stable(args))
            assertTrue(dir.resolve("prompt.txt").length() <= 864)
            stdout.writeText("[1,2]")
            LocalNativeResult(1, 1)
        }
        val result = QwenSpeakerNames(executor, model).infer(source, temporary.root.resolve("names"))
        val named = result.attach(source)
        assertEquals(1, calls)
        assertEquals(listOf("Анна", "Борис"), named.speakers.map { it.name })
        assertEquals(source.utterances, named.utterances)
        assertEquals(TranscriptExport.submissionJson(source), TranscriptExport.submissionJson(named))
        assertTrue(TranscriptExport.jsonWithMetadata(named).contains("\"speaker_name\": \"Анна\""))
        val changed = source.copy(utterances = source.utterances.map { it.copy(startMs = it.startMs + 1) })
        assertEquals(changed, result.attach(changed))
        val manual = source.copy(speakers = listOf(Speaker("speaker_1", "Ручное имя"), Speaker("speaker_2")))
        assertEquals("Ручное имя", result.attach(manual).speakers.first().name)
    }

    @Test fun `address belongs to reply and unsupported invented or conflicting assignments are rejected`() {
        val source = dialogue("Анна, расскажи о проекте.", "Мы делаем приложение.")
        val plan = SpeakerNamePlan.create(source)
        assertNotNull(plan.request)
        assertEquals("Анна", plan.resolve("[2]").attach(source).speakers[1].name)
        for (bad in listOf("[1]", "[3]", "[\"2\"]", "[2,1]", "\"Мария\"")) {
            assertTrue(runCatching { plan.resolve(bad) }.isFailure)
        }
        val introduced = dialogue("Меня зовут Анна.", "Меня зовут Борис.")
        assertTrue(runCatching { SpeakerNamePlan.create(introduced).resolve("[2,1]") }.isFailure)
    }

    @Test fun `ordinary mentions skip the model and invalid output or cancellation preserves original`() = runBlocking {
        val source = dialogue("Вчера Миша принёс запись.", "Продолжим работу.")
        val model = temporary.newFile("model")
        val noCall = LocalNativeExecutor { _, _, _, _, _, _ -> error("No identifying cue") }
        assertEquals("no_identity_cues", QwenSpeakerNames(noCall, model).infer(source, temporary.root.resolve("none")).status)
        val introduced = dialogue("Меня зовут Анна.")
        val invalid = LocalNativeExecutor { _, _, _, stdout, _, _ -> stdout.writeText("[9]"); LocalNativeResult(1, 1) }
        val result = QwenSpeakerNames(invalid, model).infer(introduced, temporary.root.resolve("bad"))
        assertEquals("invalid_response", result.status)
        assertEquals(introduced, result.attach(introduced))
        val cancelled = LocalNativeExecutor { _, _, _, _, _, _ -> throw CancellationException() }
        assertTrue(runCatching { QwenSpeakerNames(cancelled, model).infer(introduced, temporary.root.resolve("cancel")) }
            .exceptionOrNull() is CancellationException)
    }
}
