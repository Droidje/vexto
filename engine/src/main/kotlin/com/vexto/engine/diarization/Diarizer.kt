package com.vexto.engine.diarization

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.vexto.engine.audio.PcmAudio
import com.vexto.engine.audio.Segment
import com.vexto.engine.model.SampleRate
import com.vexto.engine.model.SpeakerTurn
import com.vexto.engine.models.InstalledModels
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

/**
 * Диаризация: окна 1 с с шагом 0,5 с внутри речевых сегментов VAD, эмбеддинг спикера на окно,
 * агломеративная кластеризация. На синтетических «кошачьих» диалогах DER вдвое ниже, чем у
 * встроенной в sherpa-onnx связки pyannote + FastClustering.
 */
internal object Diarizer {

    private const val WindowSamples = SampleRate
    private const val MinHopSamples = SampleRate / 2
    private const val MinSegmentSamples = SampleRate * 3 / 10

    /** Предел числа окон: кластеризация квадратична по памяти, час речи иначе дал бы 7000 окон. */
    private const val MaxWindows = 2500

    /** Кластер с меньшим объёмом речи, секунд, считается осколком другого спикера. */
    private const val MinSpeakerSec = 5f

    suspend fun run(
        models: InstalledModels,
        audio: PcmAudio,
        speech: List<Segment>,
        speakerCount: Int?,
        threads: Int,
        onProgress: (Float) -> Unit,
    ): List<SpeakerTurn> {
        val windows = windows(speech)
        if (windows.isEmpty()) return emptyList()
        val extractor = SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(model = models.path(models.manifest.embedding), numThreads = threads),
        )
        val embeddings = ArrayList<FloatArray>(windows.size)
        try {
            windows.forEachIndexed { i, w ->
                currentCoroutineContext().ensureActive()
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(audio.read(w.start, w.end, agc = true), SampleRate)
                    stream.inputFinished()
                    embeddings += normalized(extractor.compute(stream))
                } finally {
                    stream.release()
                }
                onProgress((i + 1).toFloat() / windows.size)
            }
        } finally {
            extractor.release()
        }
        val weights = FloatArray(windows.size) { (windows[it].end - windows[it].start).toFloat() / SampleRate }
        var labels = SpeakerClustering.cluster(embeddings, weights, models.manifest.clusterThreshold, speakerCount)
        if (speakerCount == null) labels = SpeakerClustering.absorbSmall(labels, embeddings, weights, MinSpeakerSec)
        return SpeakerClustering.turns(windows, labels, PcmAudio.FrameSize, SampleRate / 1000)
    }

    private fun windows(speech: List<Segment>): List<Segment> {
        val usable = speech.filter { it.end - it.start >= MinSegmentSamples }
        val total = usable.sumOf { it.end - it.start }
        val hop = maxOf(MinHopSamples.toLong(), total / MaxWindows)
        val out = mutableListOf<Segment>()
        for (s in usable) {
            if (s.end - s.start <= WindowSamples * 3 / 2) {
                out += s
                continue
            }
            var t = s.start
            while (t + WindowSamples < s.end) {
                out += Segment(t, t + WindowSamples)
                t += hop
            }
            out += Segment(s.end - WindowSamples, s.end)
        }
        return out
    }

    private fun normalized(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        val k = 1f / (sqrt(norm) + 1e-9f)
        return FloatArray(v.size) { v[it] * k }
    }
}
