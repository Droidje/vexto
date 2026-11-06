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

/** Silero VAD режет запись на куски не длиннее [MaxSpeechSec] для ASR. */
internal object VoiceDetector {

    /** Энкодер GigaAM обучен на кусках до 25 с, внимание квадратично по длине. */
    private const val MaxSpeechSec = 20f
    private const val PadSamples = SampleRate / 10L
    private const val Window = 512
    private const val ChunkWindows = 64

    suspend fun segments(models: InstalledModels, audio: PcmAudio, onProgress: (Float) -> Unit): List<Segment> {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = models.path(models.manifest.vad),
                threshold = 0.5f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.1f,
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
        return SpeechSegments.pad(split, PadSamples, audio.sampleCount)
    }
}
