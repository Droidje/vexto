package com.vexto.engine.model

/** Local speaker signature. Only vectors with the exact same modelId are comparable. */
data class VoiceEmbedding(
    val modelId: String,
    val values: List<Float>,
    val speechMs: Long,
    val windowCount: Int,
) {
    init {
        require(modelId.isNotBlank() && modelId.length <= 256)
        require(values.isNotEmpty() && values.size <= 4096 && values.all { it.isFinite() })
        require(speechMs >= 0 && windowCount > 0)
    }
}
