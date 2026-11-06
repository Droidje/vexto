package com.vexto.engine.emotion

import com.vexto.engine.audio.PcmAudio
import com.vexto.engine.model.EmotionProbabilities
import com.vexto.engine.model.SampleRate
import com.vexto.engine.model.Utterance
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Computes one complete line from its unmodified PCM, with cancellation between native windows. */
internal object UtteranceEmotions {
    private const val MaxSamples = 8 * SampleRate
    private const val MinSamples = SampleRate / 5

    suspend fun predict(
        session: GigaAmOrt,
        frontend: GigaAmMel,
        audio: PcmAudio,
        line: Utterance,
    ): EmotionProbabilities? {
        val start = (line.startMs * (SampleRate / 1000)).coerceIn(0L, audio.sampleCount)
        val end = (line.endMs * (SampleRate / 1000)).coerceIn(start, audio.sampleCount)
        val duration = end - start
        if (duration == 0L) return null

        // Evenly sized, bounded windows cover the whole line; no audio from adjacent lines is used.
        val windows = (duration + MaxSamples - 1) / MaxSamples
        var neutral = 0.0
        var joy = 0.0
        var sadness = 0.0
        var anger = 0.0
        for (i in 0L until windows) {
            currentCoroutineContext().ensureActive()
            val from = start + duration * i / windows
            val to = start + duration * (i + 1) / windows
            val clip = audio.read(from, to)
            val samples = if (clip.size >= MinSamples) clip else FloatArray(MinSamples).also {
                clip.copyInto(it, (MinSamples - clip.size) / 2)
            }
            val mel = frontend.logMel(samples)
            currentCoroutineContext().ensureActive()
            val scores = EmotionProbabilities.fromModel(session.predict(mel, GigaAmMel.frames(samples.size)))
            currentCoroutineContext().ensureActive()
            val weight = (to - from).toDouble()
            neutral += weight * scores.neutral
            joy += weight * scores.joy
            sadness += weight * scores.sadness
            anger += weight * scores.anger
        }
        return EmotionProbabilities(neutral / duration, joy / duration, sadness / duration, anger / duration)
    }
}
