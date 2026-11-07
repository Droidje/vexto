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
import java.util.concurrent.atomic.AtomicReference

/** Reuses packaged Qwen and holds the inference gate until its process is closed. */
internal class LocalConversationSummary(private val context: Context) {
    suspend fun run(
        transcript: String,
        progress: suspend (Int, Int, Long) -> Unit,
        onSummary: suspend (String) -> Unit,
        generateTitle: (() -> Boolean)?,
        onTitle: suspend (String?) -> Unit,
    ) {
        val plan = SummaryPlan(transcript)
        val language = plan.language
        val summaryPrompt = language.summaryPrompt()
        progress(0, plan.steps, -1)
        TranscriptionEngine.lock.withLock {
            withModel { session ->
                var elapsed = 0L
                var done = 0
                val summary = plan.run { input ->
                    val start = SystemClock.elapsedRealtime()
                    val result = session.generate(summaryPrompt, input, 120_000)
                    elapsed += SystemClock.elapsedRealtime() - start
                    done++
                    progress(done, plan.steps, elapsed * (plan.steps - done) / done)
                    result
                }
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
        val model = QualityModelStore.prepareSummary(context).file("qwen")
        val cache = File(context.cacheDir, "conversation-summary").apply {
            check(mkdirs() || isDirectory)
            listFiles().orEmpty().filter { it.name.startsWith("run-") }.forEach { it.deleteRecursively() }
        }
        val work = Files.createTempDirectory(cache.toPath(), "run-").toFile()
        try {
            return NativeProcessRunner(context).persistentExecutor(work).use { executor ->
                run(ModelSession(model, work, executor))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private class ModelSession(model: File, private val work: File, private val executor: PersistentNativeExecutor) {
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

        suspend fun generate(instruction: String, input: String, timeoutMs: Long): String {
            currentCoroutineContext().ensureActive()
            check(instruction.toByteArray(Charsets.UTF_8).size <= 400)
            check(input.toByteArray(Charsets.UTF_8).size <= 1400)
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
