package com.vexto.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Клиент локальной LLM для UI-процесса. Модель работает в процессе `:llm`;
 * если он упадёт, текущий вызов завершится [LlmException], а следующий поднимет процесс заново.
 *
 * Запросы выполняются по очереди: сервис обрабатывает один запрос за раз.
 */
class LlmClient @JvmOverloads constructor(
    context: Context,
    private val config: LlmConfig = LlmConfig(),
) : Closeable {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var service = CompletableDeferred<ILlmService>()
    private var bound = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Вызывается после каждого обращения к модели; удобно для замеров и логов. */
    @Volatile var statsListener: StatsListener? = null

    fun interface StatsListener {
        fun onStats(stats: GenerationStats)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            synchronized(lock) {
                if (service.isCompleted) service = CompletableDeferred()
                service.complete(ILlmService.Stub.asInterface(binder))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { service = CompletableDeferred() }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) {
                appContext.unbindService(this)
                bound = false
                service = CompletableDeferred()
            }
        }
    }

    private suspend fun service(): ILlmService {
        val deferred = synchronized(lock) {
            if (!bound) {
                bound = appContext.bindService(
                    Intent(appContext, LlmService::class.java), connection, Context.BIND_AUTO_CREATE,
                )
                if (!bound) throw LlmException("не удалось подключиться к LlmService")
            }
            service
        }
        return deferred.await()
    }

    /** Отвязывается от сервиса; процесс `:llm` завершится, когда у него не останется клиентов. */
    override fun close() {
        scope.coroutineContext.cancelChildren()
        synchronized(lock) {
            if (bound) appContext.unbindService(connection)
            bound = false
            service = CompletableDeferred()
        }
    }

    // ---------- Задачи ----------

    /** Короткое название разговора или null, если модель не дала осмысленного ответа. */
    suspend fun title(transcript: List<Utterance>): String? {
        if (transcript.isEmpty()) return null
        val first = chunks(transcript, TITLE_TOKENS).first()
        val raw = complete(Prompts.title(TranscriptText.format(first)), Prompts.TITLE_GRAMMAR, TITLE_TOKENS)
        return Validation.title(raw)
    }

    /**
     * Имена участников: для каждого speakerId из транскрипта имя или null.
     * Имя остаётся, только если оно действительно звучит в тексте.
     */
    suspend fun speakerNames(transcript: List<Utterance>): Map<String, String?> {
        val speakers = transcript.map { it.speakerId }.distinct()
        val result = LinkedHashMap<String, String?>().apply { speakers.forEach { put(it, null) } }
        if (speakers.isEmpty()) return result
        val maxTokens = 16 + speakers.size * 16
        for (chunk in chunks(transcript, maxTokens)) {
            val ids = chunk.map { it.speakerId }.distinct()
            val missing = ids.filter { result[it] == null }
            if (missing.isEmpty()) continue
            val raw = complete(
                Prompts.speakerNames(TranscriptText.format(chunk), ids),
                Prompts.speakerNamesGrammar(ids),
                maxTokens,
            )
            val text = chunk.joinToString("\n") { it.text }
            val taken = result.values.filterNotNull().map { it.lowercase() }.toSet()
            Validation.speakerNames(raw, ids, text).forEach { (id, name) ->
                if (id in missing && name != null && name.lowercase() !in taken) result[id] = name
            }
        }
        return result
    }

    /** Сводка и ключевые пункты со ссылками на существующие в транскрипте метки времени. */
    suspend fun summary(transcript: List<Utterance>): Summary {
        if (transcript.isEmpty()) return Summary("", emptyList())
        val parts = chunks(transcript, SUMMARY_TOKENS).map { chunk ->
            val stamps = Validation.timestamps(chunk)
            val raw = complete(
                Prompts.summary(TranscriptText.format(chunk)),
                Prompts.summaryGrammar(stamps.keys),
                SUMMARY_TOKENS,
            )
            Validation.summary(raw, stamps)
        }.filterNotNull()
        if (parts.size <= 1) return parts.firstOrNull() ?: Summary("", emptyList())
        val merged = complete(Prompts.mergeSummaries(parts.map { it.text }), Prompts.MERGE_GRAMMAR, MERGE_TOKENS)
        return Summary(merged.trim(), parts.flatMap { it.points }.sortedBy { it.startMs })
    }

    // ---------- API с колбэками для Java ----------

    /** Результат приходит в главном потоке. */
    interface Callback<T> {
        fun onSuccess(result: T)
        fun onError(error: Throwable)
    }

    /** Отмена вызова, начатого через *Async-метод. */
    fun interface Call {
        fun cancel()
    }

    fun titleAsync(transcript: List<Utterance>, callback: Callback<String?>): Call =
        async(callback) { title(transcript) }

    fun speakerNamesAsync(transcript: List<Utterance>, callback: Callback<Map<String, String?>>): Call =
        async(callback) { speakerNames(transcript) }

    fun summaryAsync(transcript: List<Utterance>, callback: Callback<Summary>): Call =
        async(callback) { summary(transcript) }

    private fun <T> async(callback: Callback<T>, block: suspend () -> T): Call {
        val job: Job = scope.launch {
            val result = runCatching { block() }
            if (!isActive) return@launch
            result.fold(callback::onSuccess, callback::onError)
        }
        return Call { job.cancel() }
    }

    // ---------- Низкоуровневый доступ ----------

    /** Поток кусков текста по мере генерации. Отмена коллектора отменяет генерацию. */
    fun stream(user: String, grammar: String? = null, maxTokens: Int = 256): Flow<String> = callbackFlow {
        val result = runCatching { generate(Prompts.SYSTEM, user, grammar, maxTokens) { trySend(it) } }
        close(result.exceptionOrNull())
        awaitClose()
    }

    /** Полный ответ модели на произвольный запрос. */
    suspend fun complete(user: String, grammar: String? = null, maxTokens: Int = 256): String =
        generate(Prompts.SYSTEM, user, grammar, maxTokens) {}

    private suspend fun chunks(transcript: List<Utterance>, maxOutputTokens: Int): List<List<Utterance>> {
        val budget = config.contextSize - maxOutputTokens - PROMPT_OVERHEAD_TOKENS
        val counts = countTokens(transcript.map { TranscriptText.line(it) + "\n" })
        return TranscriptText.chunk(transcript, budget, counts)
    }

    private suspend fun countTokens(lines: List<String>): IntArray = withContext(Dispatchers.IO) {
        val s = service()
        try {
            s.countTokens(Protocol.model(config), lines.toTypedArray())
        } catch (e: Exception) {
            throw LlmException("процесс LLM недоступен: ${e.message}")
        }
    }

    private suspend fun generate(
        system: String,
        user: String,
        grammar: String?,
        maxTokens: Int,
        onPiece: (String) -> Unit,
    ): String {
        val s = service()
        val request = Protocol.model(config).apply {
            putString(Protocol.SYSTEM, system)
            putString(Protocol.USER, user)
            putString(Protocol.GRAMMAR, grammar)
            putInt(Protocol.MAX_TOKENS, maxTokens)
            putFloat(Protocol.TEMPERATURE, TEMPERATURE)
        }
        return suspendCancellableCoroutine { cont ->
            val binder = s.asBinder()
            val death = IBinder.DeathRecipient {
                cont.fail(LlmException("процесс LLM завершился во время генерации"))
            }
            val callback = object : ILlmCallback.Stub() {
                override fun onPiece(piece: String) {
                    if (cont.isActive) onPiece(piece)
                }

                override fun onComplete(text: String, stats: Bundle) {
                    binder.unlinkToDeath(death, 0)
                    statsListener?.onStats(stats.toStats())
                    if (cont.isActive) cont.resume(text)
                }

                override fun onError(message: String) {
                    binder.unlinkToDeath(death, 0)
                    cont.fail(LlmException(message))
                }
            }
            try {
                binder.linkToDeath(death, 0)
                s.generate(request, callback)
            } catch (e: Exception) {
                cont.fail(LlmException("процесс LLM недоступен: ${e.message}"))
            }
            cont.invokeOnCancellation {
                runCatching { s.cancel() }
                runCatching { binder.unlinkToDeath(death, 0) }
            }
        }
    }

    private fun CancellableContinuation<String>.fail(e: Exception) {
        if (isActive) resumeWithException(e)
    }

    private fun Bundle.toStats() = GenerationStats(
        promptTokens = getInt(Protocol.STAT_PROMPT_TOKENS),
        prefillMs = getLong(Protocol.STAT_PREFILL_MS),
        generatedTokens = getInt(Protocol.STAT_GEN_TOKENS),
        generationMs = getLong(Protocol.STAT_GEN_MS),
        loadMs = getLong(Protocol.STAT_LOAD_MS),
        peakRssKb = getLong(Protocol.STAT_PEAK_RSS_KB),
        rssKb = getLong(Protocol.STAT_RSS_KB),
        backend = getString(Protocol.STAT_BACKEND).orEmpty(),
        model = getString(Protocol.STAT_MODEL).orEmpty(),
    )

    private companion object {
        const val TEMPERATURE = 0.2f
        const val TITLE_TOKENS = 24
        const val SUMMARY_TOKENS = 512
        const val MERGE_TOKENS = 256
        const val PROMPT_OVERHEAD_TOKENS = 400
    }
}
