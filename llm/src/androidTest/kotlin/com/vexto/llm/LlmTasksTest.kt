package com.vexto.llm

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Прогон трёх задач на реальной модели через процесс `:llm`. Команды запуска — в llm/README.md.
 * Аргументы инструментации: `modelPath` (по умолчанию filesDir/model.gguf), `threads`, `cpuVariant`, `repack`.
 */
@RunWith(AndroidJUnit4::class)
class LlmTasksTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val modelPath = args.getString("modelPath")
        ?: File(context.filesDir, "model.gguf").path
    private val stats = mutableListOf<GenerationStats>()
    private val config = LlmConfig(
        modelPath = modelPath,
        threads = args.getString("threads")?.toInt() ?: 0,
        cpuVariant = args.getString("cpuVariant"),
        repackWeights = args.getString("repack").toBoolean(),
    )
    private val client = LlmClient(context, config)
        .apply { statsListener = LlmClient.StatsListener { synchronized(stats) { stats += it } } }

    @After
    fun tearDown() = client.close()

    @Test
    fun runsAllTasksOnFixture() = runBlocking {
        report("config=$config")

        val title = timed("title") { client.title(Fixture.transcript) }
        report("title: $title")
        assertNotNull(title)

        val names = timed("speakerNames") { client.speakerNames(Fixture.transcript) }
        report("speakerNames: $names")
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3"), names.keys.toList())
        val allText = Fixture.transcript.joinToString(" ") { it.text }
        names.values.filterNotNull().forEach { assertTrue(it, Validation.isNameInText(it, allText)) }

        val summary = timed("summary") { client.summary(Fixture.transcript) }
        report("summary: ${summary.text}")
        summary.points.forEach { report("  [${it.timestamp}] ${it.text}") }
        assertTrue(summary.text.isNotBlank())
        val stamps = Fixture.transcript.map { TranscriptText.timestamp(it.startMs) }.toSet()
        summary.points.forEach { assertTrue(it.timestamp, it.timestamp in stamps) }
    }

    private suspend fun <T> timed(name: String, block: suspend () -> T): T {
        val from = synchronized(stats) { stats.size }
        val t0 = SystemClock.elapsedRealtime()
        val result = block()
        val wall = SystemClock.elapsedRealtime() - t0
        val calls = synchronized(stats) { stats.subList(from, stats.size).toList() }
        report("$name: $wall мс, вызовов модели ${calls.size}")
        calls.forEach {
            report(
                "  load=%d мс, prompt=%d ток за %d мс (%.1f ток/с), gen=%d ток за %d мс (%.1f ток/с), RSS=%d МиБ (anon %d), пик=%d МиБ, backend=%s"
                    .format(
                        it.loadMs, it.promptTokens, it.prefillMs, it.prefillTokensPerSec,
                        it.generatedTokens, it.generationMs, it.generationTokensPerSec,
                        it.rssKb / 1024, it.rssAnonKb / 1024, it.peakRssKb / 1024, it.backend,
                    ),
            )
        }
        return result
    }

    private fun report(line: String) {
        Log.i("VextoLlmTest", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
    }
}
