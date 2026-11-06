package com.vexto.llm

/**
 * Настройки локальной модели.
 *
 * @param modelPath путь к GGUF на устройстве; null — модель из assets модуля
 *   (при первом запуске копируется во внутреннее хранилище).
 * @param threads 0 — min(4, число ядер).
 * @param cpuVariant принудительный вариант CPU-бэкенда ggml (например `sse42`); null — по /proc/cpuinfo.
 * @param idleUnloadMs через сколько миллисекунд простоя выгрузить модель из памяти.
 */
data class LlmConfig @JvmOverloads constructor(
    val modelPath: String? = null,
    val contextSize: Int = 4096,
    val threads: Int = 0,
    val cpuVariant: String? = null,
    val idleUnloadMs: Long = 60_000,
)

/** Замеры одного вызова модели; [peakRssKb] — пик RSS процесса `:llm` за всё время его жизни. */
data class GenerationStats(
    val promptTokens: Int,
    val prefillMs: Long,
    val generatedTokens: Int,
    val generationMs: Long,
    val loadMs: Long,
    val peakRssKb: Long,
    val rssKb: Long,
    val backend: String,
    val model: String,
) {
    val prefillTokensPerSec: Double get() = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0
    val generationTokensPerSec: Double get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0
}

class LlmException(message: String) : Exception(message)
