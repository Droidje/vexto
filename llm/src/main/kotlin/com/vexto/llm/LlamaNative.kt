package com.vexto.llm

/** JNI к llama.cpp. Используется только в процессе `:llm` (см. [LlmService]). */
internal object LlamaNative {

    fun interface PieceCallback {
        fun onPiece(utf8: ByteArray)
    }

    init {
        System.loadLibrary("vexto_llm")
    }

    /** Загружает CPU-бэкенд ggml и возвращает имя выбранного варианта (haswell, sse42, ...). */
    external fun nativeInit(forcedVariant: String?): String

    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, repack: Boolean): Long

    external fun nativeCountTokens(handle: Long, texts: Array<String>): IntArray

    /** Блокирует поток до конца генерации. Возвращает [токены промпта, мс, токены ответа, мс]. */
    external fun nativeGenerate(
        handle: Long,
        system: String,
        user: String,
        grammar: String?,
        maxTokens: Int,
        temperature: Float,
        callback: PieceCallback,
    ): LongArray?

    external fun nativeCancel(handle: Long)

    external fun nativeFree(handle: Long)
}
