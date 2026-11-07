package com.vexto.engine.pipeline

import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import com.vexto.engine.audio.PcmWave
import java.io.File

/**
 * Language of the first 30 seconds, in the app process.
 * Diarization already owns the single `:inference` worker, so this call overlaps it.
 */
object SpokenLanguageIdentifier {
    fun identifyRecording(encoder: File, decoder: File, wav: File): RecordingLanguage {
        val info = PcmWave.inspect(wav)
        val count = minOf(info.sampleCount, RecordingLanguage.WINDOW_SAMPLES.toLong()).toInt()
        require(count > 0)
        val audio = PcmWave.readFloat(wav, 0, count)
        return RecordingLanguage.fromDetection(identify(encoder, decoder, audio, info.sampleRate), count)
    }

    fun identify(encoder: File, decoder: File, samples: FloatArray, sampleRate: Int): String {
        require(encoder.isFile && decoder.isFile)
        require(sampleRate == 16000 && samples.isNotEmpty() && samples.size <= RecordingLanguage.WINDOW_SAMPLES)
        require(samples.all { it.isFinite() })
        val lid = SpokenLanguageIdentification(
            config = SpokenLanguageIdentificationConfig(
                whisper = SpokenLanguageIdentificationWhisperConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    tailPaddings = 0,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        try {
            val stream = lid.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                val code = lid.compute(stream).trim().lowercase()
                require(code.isNotEmpty()) { "empty_language" }
                return code
            } finally {
                stream.release()
            }
        } finally {
            lid.release()
        }
    }
}
