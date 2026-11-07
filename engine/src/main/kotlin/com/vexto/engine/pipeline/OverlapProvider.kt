package com.vexto.engine.pipeline

import com.vexto.engine.audio.PcmWave
import com.vexto.engine.models.OverlapManifest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File

/** Frozen WavLM encoder plus calibrated head. Runs only inside a bounded inference worker. */
class OverlapProvider private constructor(private val factory: () -> Predictor) : Closeable {
    constructor(model: File, threads: Int = 2) : this({ NativePredictor(model, threads) })
    internal constructor(predictor: Predictor) : this({ predictor })
    internal interface Predictor : Closeable { fun predict(audio: FloatArray): Float }
    private var predictor: Predictor? = null

    suspend fun annotate(
        originalWav: File,
        targets: List<UtteranceOverlapTarget>,
        modelRevision: String,
        checkpoint: (List<UtteranceOverlapEvidence>) -> Unit = {},
    ): List<UtteranceOverlapEvidence> {
        val info = PcmWave.inspect(originalWav)
        require(targets.map { it.index }.distinct().size == targets.size)
        val results = mutableListOf<UtteranceOverlapEvidence>()
        for (target in targets) {
            currentCoroutineContext().ensureActive()
            require(target.endMs <= info.sampleCount / 16) { "Overlap target outside original audio" }
            var maxScore: Double? = null
            var windows = 0
            for (start in OverlapWindows.starts(target.startMs, target.endMs)) {
                currentCoroutineContext().ensureActive()
                val runtime = predictor ?: factory().also { predictor = it }
                val score = runtime.predict(PcmWave.readFloat(originalWav, start, OverlapManifest.WindowSamples)).toDouble()
                require(score.isFinite() && score in 0.0..1.0) { "Invalid overlap score" }
                maxScore = maxOf(maxScore ?: score, score)
                windows++
            }
            currentCoroutineContext().ensureActive()
            results += UtteranceOverlapEvidence(target, maxScore, windows, modelRevision)
            checkpoint(results.toList())
        }
        return results
    }

    override fun close() { predictor?.close(); predictor = null }

    private class NativePredictor(model: File, threads: Int) : Predictor {
        private var handle: Long
        init {
            require(model.isFile && threads in 1..4)
            handle = OverlapNative.create(model.absolutePath, threads)
            check(handle != 0L)
        }
        @Synchronized override fun predict(audio: FloatArray): Float {
            check(handle != 0L)
            require(audio.size == OverlapManifest.WindowSamples)
            return OverlapNative.predict(handle, audio)
        }
        @Synchronized override fun close() {
            if (handle != 0L) OverlapNative.release(handle)
            handle = 0L
        }
    }
}

/** Reuses sherpa's sole packaged ONNX Runtime through the native C API. */
internal object OverlapNative {
    init { System.loadLibrary("vexto_overlap_jni") }
    external fun create(modelPath: String, threads: Int): Long
    external fun predict(handle: Long, audio: FloatArray): Float
    external fun release(handle: Long)
}
