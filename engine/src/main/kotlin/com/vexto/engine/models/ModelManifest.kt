package com.vexto.engine.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Набор моделей в `assets/models/manifest.json`. Пишется scripts/fetch_models.sh, поэтому
 * смена ASR или эмбеддингов спикера не требует правок кода.
 */
@Serializable
data class ModelManifest(
    /** Меняется при любой смене файлов: по нему установщик понимает, что копию надо обновить. */
    val version: String,
    val asr: AsrModel,
    val vad: String,
    val segmentation: String,
    val embedding: String,
    /** Порог кластеризации спикеров: больше порог, меньше спикеров. */
    val clusterThreshold: Float = 0.5f,
) {
    /** Все файлы относительно каталога моделей. */
    val files: List<String>
        get() = listOfNotNull(asr.encoder, asr.decoder, asr.joiner, asr.model, asr.tokens, vad, segmentation, embedding)

    companion object {
        private val JsonFormat = Json { ignoreUnknownKeys = true }

        fun parse(json: String): ModelManifest = JsonFormat.decodeFromString(serializer(), json)
    }
}

@Serializable
data class AsrModel(
    val id: String,
    /** `nemo_transducer` (GigaAM RNNT) или `nemo_ctc` (GigaAM CTC). */
    val type: String,
    val encoder: String? = null,
    val decoder: String? = null,
    val joiner: String? = null,
    val model: String? = null,
    val tokens: String,
    val featureDim: Int = 64,
    /** Модель сама ставит знаки препинания и заглавные буквы. */
    val punctuation: Boolean = false,
)

/** Манифест с абсолютными путями к файлам: sherpa-onnx грузит модели по пути. */
class InstalledModels(val dir: File, val manifest: ModelManifest) {
    fun path(relative: String): String = File(dir, relative).absolutePath
}
