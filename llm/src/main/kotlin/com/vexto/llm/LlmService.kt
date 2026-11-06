package com.vexto.llm

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Держит модель в процессе `:llm` и выполняет запросы по одному.
 * Модель грузится при первом запросе и выгружается после простоя или по onTrimMemory.
 */
class LlmService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "llm-worker") }
    private val main = Handler(Looper.getMainLooper())
    private val unloadTask = Runnable { worker.execute { unloadNow() } }

    // Поля ниже меняются только в потоке worker; handle читается из binder-потока для отмены.
    @Volatile private var handle = 0L
    @Volatile private var busy = false
    private var loadedKey: String? = null
    private var backend: String? = null
    // Время последней загрузки, ещё не отданное клиенту: модель может загрузиться и при подсчёте токенов.
    private var unreportedLoadMs = 0L

    private val binder = object : ILlmService.Stub() {
        override fun generate(request: Bundle, callback: ILlmCallback) {
            main.removeCallbacks(unloadTask)
            worker.execute { runGenerate(request, callback) }
        }

        override fun cancel() {
            val h = handle
            if (busy && h != 0L) LlamaNative.nativeCancel(h)
        }

        override fun countTokens(model: Bundle, texts: Array<String>): IntArray {
            main.removeCallbacks(unloadTask)
            val result = worker.submit<IntArray> {
                ensureLoaded(model)
                LlamaNative.nativeCountTokens(handle, texts)
            }
            try {
                return result.get()
            } catch (e: ExecutionException) {
                // Через Binder до клиента доходят только стандартные исключения.
                throw IllegalStateException(e.cause?.message ?: "ошибка подсчёта токенов")
            } finally {
                scheduleUnload(model)
            }
        }

        override fun unload() {
            main.removeCallbacks(unloadTask)
            worker.execute { unloadNow() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !busy) {
            main.removeCallbacks(unloadTask)
            worker.execute { unloadNow() }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(unloadTask)
        worker.execute { unloadNow() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun runGenerate(request: Bundle, callback: ILlmCallback) {
        busy = true
        try {
            ensureLoaded(request)
            val loadMs = unreportedLoadMs.also { unreportedLoadMs = 0 }
            val text = StringBuilder()
            val stats = LlamaNative.nativeGenerate(
                handle,
                request.getString(Protocol.SYSTEM).orEmpty(),
                request.getString(Protocol.USER).orEmpty(),
                request.getString(Protocol.GRAMMAR),
                request.getInt(Protocol.MAX_TOKENS, 256),
                request.getFloat(Protocol.TEMPERATURE, 0.2f),
            ) { bytes ->
                val piece = String(bytes, Charsets.UTF_8)
                text.append(piece)
                callback.onPiece(piece)
            } ?: throw LlmException("генерация отменена")
            callback.onComplete(text.toString(), statsBundle(stats, loadMs))
        } catch (t: Throwable) {
            Log.e(TAG, "ошибка генерации", t)
            try {
                callback.onError(t.message ?: t.javaClass.simpleName)
            } catch (_: Exception) {
                // Клиент уже отключился.
            }
        } finally {
            busy = false
            scheduleUnload(request)
        }
    }

    private fun ensureLoaded(spec: Bundle) {
        val path = ModelFiles.resolve(this, spec.getString(Protocol.MODEL_PATH))
        val nCtx = spec.getInt(Protocol.N_CTX, 4096)
        val threads = spec.getInt(Protocol.N_THREADS, 0).takeIf { it > 0 }
            ?: minOf(4, Runtime.getRuntime().availableProcessors())
        val repack = spec.getBoolean(Protocol.REPACK)
        val key = "$path|$nCtx|$threads|$repack"
        if (key == loadedKey && handle != 0L) return
        unloadNow()
        if (backend == null) backend = LlamaNative.nativeInit(spec.getString(Protocol.CPU_VARIANT))
        val t0 = SystemClock.elapsedRealtime()
        handle = LlamaNative.nativeLoad(path.absolutePath, nCtx, threads, repack)
        unreportedLoadMs = SystemClock.elapsedRealtime() - t0
        loadedKey = key
        Log.i(TAG, "модель ${path.name} загружена за $unreportedLoadMs мс, ctx=$nCtx, потоков=$threads, backend=$backend")
    }

    private fun unloadNow() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        loadedKey = null
        LlamaNative.nativeFree(h)
        Log.i(TAG, "модель выгружена")
    }

    private fun scheduleUnload(spec: Bundle) {
        main.removeCallbacks(unloadTask)
        main.postDelayed(unloadTask, spec.getLong(Protocol.IDLE_UNLOAD_MS, 60_000))
    }

    private fun statsBundle(s: LongArray, loadMs: Long) = Bundle().apply {
        putInt(Protocol.STAT_PROMPT_TOKENS, s[0].toInt())
        putLong(Protocol.STAT_PREFILL_MS, s[1])
        putInt(Protocol.STAT_GEN_TOKENS, s[2].toInt())
        putLong(Protocol.STAT_GEN_MS, s[3])
        putLong(Protocol.STAT_LOAD_MS, loadMs)
        val status = readStatus()
        putLong(Protocol.STAT_PEAK_RSS_KB, status["VmHWM"] ?: 0)
        putLong(Protocol.STAT_RSS_KB, status["VmRSS"] ?: 0)
        putLong(Protocol.STAT_RSS_ANON_KB, status["RssAnon"] ?: 0)
        putString(Protocol.STAT_BACKEND, backend)
        putString(Protocol.STAT_MODEL, loadedKey?.substringBefore('|')?.let { File(it).name })
    }

    private fun readStatus(): Map<String, Long> =
        File("/proc/self/status").readLines()
            .filter { it.startsWith("Vm") || it.startsWith("Rss") }
            .associate { line ->
                val (k, v) = line.split(':', limit = 2)
                k to (v.trim().substringBefore(' ').toLongOrNull() ?: 0)
            }

    private companion object {
        const val TAG = "VextoLlm"
    }
}
