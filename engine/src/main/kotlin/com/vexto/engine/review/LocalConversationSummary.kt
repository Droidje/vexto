package com.vexto.engine.review

import android.content.Context
import android.os.SystemClock
import com.vexto.engine.TranscriptionEngine
import com.vexto.engine.models.QualityModelStore
import com.vexto.engine.runtime.NativeProcessRunner
import com.vexto.engine.runtime.PersistentNativeExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicReference

/** Reuses packaged Qwen and holds the inference gate until its process is closed. */
internal class LocalConversationSummary(private val context: Context) {
    suspend fun run(
        transcript: String,
        checkpointDirectory: File?,
        progress: suspend (Int, Int, Long) -> Unit,
        onSummary: suspend (String) -> Unit,
        generateTitle: (() -> Boolean)?,
        onTitle: suspend (String?) -> Unit,
    ) {
        val plan = SummaryPlan(transcript)
        val language = plan.language
        val summaryPrompt = language.summaryPrompt()
        progress(0, 0, -1)
        TranscriptionEngine.lock.withLock {
            withModel { session ->
                val tokenPlan = TokenSummaryPlan(transcript)
                val checkpoints = checkpointDirectory?.let {
                    SummaryCheckpoints(it, "token-summary-v1\n${session.modelSha}\n$summaryPrompt\n${tokenPlan.source}")
                }
                var elapsed = 0L
                var completed = 0
                val summary = tokenPlan.run(
                    fitPrefix = { text, suffix -> session.fitPrefix(summaryPrompt, text, suffix) },
                    fits = { session.fits(summaryPrompt, it) },
                    summarize = { input ->
                        val generate: suspend () -> String = {
                            val start = SystemClock.elapsedRealtime()
                            val result = SummaryPlan.finishSummary(session.generate(summaryPrompt, input, 120_000))
                            elapsed += SystemClock.elapsedRealtime() - start
                            completed++
                            result
                        }
                        checkpoints?.response(input, generate) ?: generate()
                    },
                    progress = { done, total ->
                        progress(done, total, if (completed == 0) -1 else elapsed * (total - done) / completed)
                    },
                )
                onSummary(summary)

                // The caller checks title eligibility again at dispatch time. Keep the same
                // process only for this single extra request while the weights are resident.
                if (generateTitle != null) {
                    val attempted = try { generateTitle() } catch (_: Exception) { false }
                    if (attempted) {
                        val title = try {
                            titleRequest { session.title(ConversationTitle.input(plan), language) } ?: return@withModel
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }
                        onTitle(title)
                    }
                }
            }
        }
    }

    suspend fun tryTitle(transcript: String, eligible: (() -> Boolean)?): String? {
        val lock = TranscriptionEngine.lock
        if (!lock.tryLock()) return null
        try {
            return titleRequest {
                if (eligible?.invoke() == false) return@titleRequest null
                val plan = SummaryPlan(transcript)
                val input = ConversationTitle.input(plan)
                withModel {
                    if (eligible?.invoke() == false) null else it.title(input, plan.language)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun <T> withModel(run: suspend (ModelSession) -> T): T {
        val models = QualityModelStore.prepareSummary(context)
        val model = models.file("qwen")
        val cache = File(context.cacheDir, "conversation-summary").apply {
            check(mkdirs() || isDirectory)
            listFiles().orEmpty().filter { it.name.startsWith("run-") }.forEach { it.deleteRecursively() }
        }
        val work = Files.createTempDirectory(cache.toPath(), "run-").toFile()
        try {
            return NativeProcessRunner(context).persistentExecutor(work).use { executor ->
                run(ModelSession(model, models.manifest.artifacts.getValue("qwen").sha256, work, executor))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private class ModelSession(model: File, val modelSha: String, private val work: File, private val executor: PersistentNativeExecutor) {
        private val system = File(work, "system.txt")
        private val prompt = File(work, "prompt.txt")
        private val output = File(work, "result.txt")
        private val errors = File(work, "worker.stderr")
        private val args = listOf(
            "-m", model.absolutePath, "-c", "2048", "-n", "192", "-b", "128", "-ub", "64",
            "-t", "2", "--threads-batch", "2", "--summary-system-file", system.absolutePath,
            "-f", prompt.absolutePath,
        )

        suspend fun title(input: String, language: ConversationLanguage): String = ConversationTitle.normalize(
            generate(language.titlePrompt(), input, 45_000),
        )

        suspend fun fits(instruction: String, input: String): Boolean =
            budget(instruction, input, listOf("--count-tokens", "1"))["accepted"]!!.jsonPrimitive.boolean

        suspend fun fitPrefix(instruction: String, input: String, suffix: String): Int {
            val file = File(work, "suffix.txt").apply { writeText(suffix) }
            return budget(instruction, input, listOf("--fit-prefix", "1", "--summary-suffix-file", file.absolutePath))[
                "prefix_bytes"]!!.jsonPrimitive.int
        }

        private suspend fun budget(instruction: String, input: String, operation: List<String>): JsonObject {
            currentCoroutineContext().ensureActive()
            require(instruction.toByteArray().size <= 400 && input.toByteArray().size <= 32768)
            system.writeText(instruction)
            prompt.writeText(input)
            executor.run("libvexto_llama_exec.so", args + operation, work, output, errors, 120_000)
            check(output.length() in 1..4096)
            return Json.parseToJsonElement(output.readText()).jsonObject
        }

        suspend fun generate(instruction: String, input: String, timeoutMs: Long): String {
            currentCoroutineContext().ensureActive()
            check(instruction.toByteArray(Charsets.UTF_8).size <= 400)
            check(input.toByteArray(Charsets.UTF_8).size <= 32768)
            system.writeText(instruction)
            prompt.writeText(input)
            executor.run("libvexto_llama_exec.so", args, work, output, errors, timeoutMs)
            check(output.length() in 1L..SummaryPlan.OutputBytes.toLong()) { "Invalid model response size" }
            return output.readText().trim().also {
                check(it.isNotBlank()) { "Empty model response" }
            }
        }
    }

    companion object {
        private val activeTitle = AtomicReference<Job?>()

        fun cancelTitle() { activeTitle.get()?.cancel() }

        /** The caller retains the inference lock until this child and its native request close. */
        private suspend fun titleRequest(run: suspend () -> String?): String? = supervisorScope {
            val work = async(start = CoroutineStart.LAZY) { run() }
            check(activeTitle.compareAndSet(null, work))
            try {
                // Register before testing for ASR to cover requests arriving at the same time.
                if (TranscriptionEngine.isTranscriptionPending) return@supervisorScope null
                work.await()
            } catch (cancel: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                work.cancel()
                activeTitle.compareAndSet(work, null)
            }
        }
    }
}
