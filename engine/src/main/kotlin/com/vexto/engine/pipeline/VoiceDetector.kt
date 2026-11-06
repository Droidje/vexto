package com.vexto.engine.pipeline

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.vexto.engine.audio.PcmAudio
import com.vexto.engine.audio.Segment
import com.vexto.engine.audio.SpeechSegments
import com.vexto.engine.model.SampleRate
import com.vexto.engine.models.InstalledModels
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Silero VAD режет запись на речевые куски не длиннее [MaxSpeechSec]. */
internal object VoiceDetector {

    /** Настройки под ASR: поля 0,1 с, чтобы распознаватель слышал начало и конец слов. */
    val Recognition = Params(minSilenceSec = 0.3f, padSamples = SampleRate / 10L)

    /** Настройки под диаризацию: мельче паузы, без полей, чтобы окна не захватывали соседа. */
    val Diarization = Params(minSilenceSec = 0.2f, padSamples = 0)

    class Params(val minSilenceSec: Float, val padSamples: Long)

    /** Энкодер GigaAM обучен на кусках до 25 с, внимание квадратично по длине. */
    private const val MaxSpeechSec = 20f
    private const val Window = 512
    private const val ChunkWindows = 64

    suspend fun segments(
        models: InstalledModels,
        audio: PcmAudio,
        params: Params,
        onProgress: (Float) -> Unit,
    ): List<Segment> {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = models.path(models.manifest.vad),
                threshold = 0.5f,
                minSilenceDuration = params.minSilenceSec,
                minSpeechDuration = 0.2f,
                windowSize = Window,
                maxSpeechDuration = MaxSpeechSec,
            ),
            sampleRate = SampleRate,
            numThreads = 1,
        )
        val vad = Vad(config = config)
        val raw = mutableListOf<Segment>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                raw += Segment(s.start, s.start + s.samples.size)
                vad.pop()
            }
        }
        try {
            val chunk = Window * ChunkWindows
            var pos = 0L
            while (pos < audio.sampleCount) {
                currentCoroutineContext().ensureActive()
                val samples = audio.read(pos, pos + chunk, agc = true)
                var offset = 0
                while (offset + Window <= samples.size) {
                    vad.acceptWaveform(samples.copyOfRange(offset, offset + Window))
                    offset += Window
                }
                drain()
                pos += chunk
                onProgress(pos.toFloat() / audio.sampleCount)
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        val maxSamples = (MaxSpeechSec * SampleRate).toInt()
        val split = raw.flatMap { s -> SpeechSegments.splitLong(s, maxSamples) { a, b -> audio.read(a, b, agc = true) } }
        return SpeechSegments.pad(split, params.padSamples, audio.sampleCount)
    }
}
