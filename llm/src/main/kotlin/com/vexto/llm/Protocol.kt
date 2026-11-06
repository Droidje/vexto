package com.vexto.llm

import android.os.Bundle

/** Ключи Bundle между клиентом и [LlmService]. */
internal object Protocol {
    const val MODEL_PATH = "modelPath"
    const val N_CTX = "nCtx"
    const val N_THREADS = "nThreads"
    const val CPU_VARIANT = "cpuVariant"
    const val IDLE_UNLOAD_MS = "idleUnloadMs"

    const val SYSTEM = "system"
    const val USER = "user"
    const val GRAMMAR = "grammar"
    const val MAX_TOKENS = "maxTokens"
    const val TEMPERATURE = "temperature"

    const val STAT_PROMPT_TOKENS = "promptTokens"
    const val STAT_PREFILL_MS = "prefillMs"
    const val STAT_GEN_TOKENS = "genTokens"
    const val STAT_GEN_MS = "genMs"
    const val STAT_LOAD_MS = "loadMs"
    const val STAT_PEAK_RSS_KB = "peakRssKb"
    const val STAT_RSS_KB = "rssKb"
    const val STAT_BACKEND = "backend"
    const val STAT_MODEL = "model"

    fun model(config: LlmConfig) = Bundle().apply {
        putString(MODEL_PATH, config.modelPath)
        putInt(N_CTX, config.contextSize)
        putInt(N_THREADS, config.threads)
        putString(CPU_VARIANT, config.cpuVariant)
        putLong(IDLE_UNLOAD_MS, config.idleUnloadMs)
    }
}
