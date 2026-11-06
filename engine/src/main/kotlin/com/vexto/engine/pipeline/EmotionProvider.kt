package com.vexto.engine.pipeline

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.SpeakerTurn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File

/** GigaAM-Emo v1, original audio only. Predictions are evidence, not verified feelings. */
class EmotionProvider private constructor(private val factory: () -> Predictor) : Closeable {
    constructor(model: File, threads: Int = 2) : this({ NativePredictor(model, threads) })
    internal constructor(predictor: Predictor) : this({ predictor })
    internal interface Predictor : Closeable {
        val runtimeVersion: String
        fun predict(features: FloatArray, frames: Int): FloatArray
    }
    private var predictor: Predictor? = null

    suspend fun annotate(originalWav: File, turns: List<SpeakerTurn>, artifactFile: File): List<EmotionEvidence> {
        require(!artifactFile.exists()) { "Emotion artifact must be fresh" }
        val info = PcmWave.inspect(originalWav)
        require(info.sampleRate == 16000)
        val clips = EmotionSelection.select(turns, info.sampleCount*1000/16000)
        val labels = listOf("angry", "sad", "neutral", "positive")
        val evidence = clips.map { clip ->
            currentCoroutineContext().ensureActive()
            val first = clip.startMs*16; val count = ((clip.endMs-clip.startMs)*16).toInt()
            val audio = PcmWave.readFloat(originalWav,first,count)
            val features = EmotionFrontend.logMel(audio)
            val runtime = predictor ?: factory().also { predictor = it }
            val probs = runtime.predict(features,audio.size/160+1)
            require(probs.size == 4 && probs.all { it.isFinite() && it in 0f..1f })
            require(kotlin.math.abs(probs.sum()-1) < 0.001f)
            val best = probs.indices.maxBy { probs[it] }
            EmotionEvidence(clip.speakerId,clip.startMs,clip.endMs,labels[best],probs[best],labels.zip(probs.toList()).toMap())
        }
        val payload = buildJsonObject {
            put("model", "GigaAM-Emo-v1-third-party-dynamic-int8")
            put("status", if(evidence.isEmpty()) "no_eligible_clean_utterance" else "completed")
            put("source", "model_prediction_not_true_emotion")
            put("runtime", predictor?.runtimeVersion ?: "not_loaded")
            put("segments", Json.parseToJsonElement(Json { encodeDefaults = true }.encodeToString(evidence)))
        }
        artifactFile.parentFile?.mkdirs()
        artifactFile.writeText(payload.toString())
        return evidence
    }
    override fun close() { predictor?.close(); predictor = null }

    private class NativePredictor(model: File, threads: Int) : Predictor {
        private var handle: Long
        init {
            require(model.isFile && threads in 1..4)
            handle = EmotionNative.create(model.absolutePath,threads)
            check(handle != 0L)
        }
        override val runtimeVersion: String get() = EmotionNative.runtimeVersion()
        @Synchronized override fun predict(features: FloatArray, frames: Int): FloatArray {
            check(handle != 0L)
            require(frames in 1..801 && features.size == 64*frames)
            return EmotionNative.predict(handle,features,frames)
        }
        @Synchronized override fun close() { if(handle != 0L) EmotionNative.release(handle); handle = 0L }
    }
}

/** No Java ORT AAR: the JNI adapter dlopens sherpa's existing libonnxruntime.so. */
internal object EmotionNative {
    init { System.loadLibrary("vexto_emotion_jni") }
    external fun create(modelPath: String, threads: Int): Long
    external fun predict(handle: Long, features: FloatArray, frames: Int): FloatArray
    external fun release(handle: Long)
    external fun runtimeVersion(): String
}
