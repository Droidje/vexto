package com.vexto.engine.pipeline

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.vexto.engine.models.InstalledModels
import com.vexto.engine.model.SpeakerTurn

/** pyannote segmentation 3.0 + эмбеддинги спикера + быстрая кластеризация sherpa-onnx. */
internal object Diarizer {

    /**
     * Дефолты sherpa (0.2 / 0.5 с) выкидывают короткие ответы «да», «мяу» и склеивают быстрые
     * перебивания, поэтому пороги ниже.
     */
    private const val MinDurationOn = 0.08f
    private const val MinDurationOff = 0.2f

    fun run(
        models: InstalledModels,
        samples: FloatArray,
        speakerCount: Int?,
        threads: Int,
        onProgress: (Float) -> Unit,
    ): List<SpeakerTurn> {
        val m = models.manifest
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = models.path(m.segmentation)),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = models.path(m.embedding), numThreads = threads),
            clustering = FastClusteringConfig(numClusters = speakerCount ?: -1, threshold = m.clusterThreshold),
            minDurationOn = MinDurationOn,
            minDurationOff = MinDurationOff,
        )
        val diarization = OfflineSpeakerDiarization(config = config)
        try {
            val segments = diarization.processWithCallback(samples, { done, total, _ ->
                if (total > 0) onProgress(done.toFloat() / total)
                0
            }, 0L)
            return segments.map { SpeakerTurn(it.speaker, (it.start * 1000).toLong(), (it.end * 1000).toLong()) }
        } finally {
            diarization.release()
        }
    }
}
