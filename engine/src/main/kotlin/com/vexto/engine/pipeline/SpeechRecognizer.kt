package com.vexto.engine.pipeline

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.vexto.engine.audio.PcmAudio
import com.vexto.engine.audio.Segment
import com.vexto.engine.model.SampleRate
import com.vexto.engine.model.TokenWords
import com.vexto.engine.model.Word
import com.vexto.engine.models.InstalledModels
import java.io.Closeable

/** GigaAM через sherpa-onnx: сегмент записи в слова с таймкодами. */
internal class SpeechRecognizer(models: InstalledModels, threads: Int) : Closeable {

    private val recognizer: OfflineRecognizer

    init {
        val asr = models.manifest.asr
        val modelConfig = when (asr.type) {
            "nemo_transducer" -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.path(asr.encoder!!),
                    decoder = models.path(asr.decoder!!),
                    joiner = models.path(asr.joiner!!),
                ),
                modelType = "nemo_transducer",
            )
            "nemo_ctc" -> OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig(model = models.path(asr.model!!)))
            else -> throw IllegalArgumentException("Неизвестный тип ASR-модели: ${asr.type}")
        }.apply {
            tokens = models.path(asr.tokens)
            numThreads = threads
        }
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SampleRate, featureDim = asr.featureDim),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            ),
        )
    }

    fun recognize(audio: PcmAudio, segment: Segment): List<Word> {
        val samples = audio.read(segment.start, segment.end, agc = true)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val startMs = segment.start * 1000 / SampleRate
            val endMs = segment.end * 1000 / SampleRate
            return if (result.timestamps.size == result.tokens.size && result.tokens.isNotEmpty()) {
                TokenWords.group(result.tokens, result.timestamps, startMs, endMs)
            } else {
                TokenWords.spread(result.text, startMs, endMs)
            }
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
