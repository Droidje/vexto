package com.vexto.engine.emotion

import android.util.Log
import com.vexto.engine.audio.PcmAudio
import com.vexto.engine.model.EmotionProbabilities
import com.vexto.engine.model.SampleRate
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Runs one CPU session after the recognizer is closed, against each line's unmodified audio. */
internal object UtteranceEmotions {
    private const val MaxSamples = 8 * SampleRate
    private const val MinSamples = SampleRate / 5
    private const val Tag = "VextoEmotion"

    suspend fun annotate(
        model: String,
        audio: PcmAudio,
        transcript: Transcript,
        threads: Int,
        onProgress: (Float) -> Unit,
    ): Transcript {
        val annotated = ArrayList<Utterance>(transcript.utterances.size)
        try {
            currentCoroutineContext().ensureActive()
            GigaAmOrt(model, threads.coerceIn(1, 2)).use { session ->
                val frontend = GigaAmMel()
                transcript.utterances.forEachIndexed { index, line ->
                    val scores = predict(session, frontend, audio, line)
                    annotated.add(line.copy(emotionProbabilities = scores))
                    onProgress((index + 1f) / transcript.utterances.size)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            Log.w(Tag, "Audio is unavailable for emotion analysis")
        } catch (failure: RuntimeException) {
            Log.w(Tag, "Emotion analysis stopped (${failure.javaClass.simpleName})")
        } catch (failure: LinkageError) {
            Log.w(Tag, "Emotion runtime unavailable (${failure.javaClass.simpleName})")
        }
        currentCoroutineContext().ensureActive()
        if (annotated.isEmpty()) return transcript
        // Keep completed scores and the full text even when the optional stage fails.
        return transcript.copy(utterances = annotated + transcript.utterances.drop(annotated.size))
    }

    private suspend fun predict(
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
            val scores = EmotionProbabilities.fromModel(session.predict(frontend.logMel(samples), GigaAmMel.frames(samples.size)))
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
