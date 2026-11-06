package com.vexto.engine.pipeline

import com.k2fsa.sherpa.onnx.*
import com.vexto.engine.audio.PcmWave
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File

@Serializable
data class DenoiseAudit(
    val originalSamples: Long,
    val filteredSamples: Long,
    val outputSamples: Long,
    val tailPaddingSamples: Long,
    val tailTruncatedSamples: Long,
    val alignmentVerified: Boolean = false,
    val sampleRate: Int = 16000,
    val method: String = "streaming_gtcrn_then_restore_tail_sample_count",
)

/** A separate ASR copy; original PCM must remain the input to diarization and emotion. */
class GtcrnDenoiserProvider internal constructor(private val processor: Processor) : Closeable {
    constructor(model: File, threads: Int = 2) : this(SherpaProcessor(model, threads))

    internal interface Processor : Closeable {
        fun reset()
        fun run(samples: FloatArray): FloatArray
        fun flush(): FloatArray
    }

    suspend fun denoise(inputWav: File, outputWav: File, auditFile: File): DenoiseAudit {
        require(inputWav.canonicalFile != outputWav.canonicalFile) { "Denoising must preserve original audio" }
        require(auditFile.canonicalFile != outputWav.canonicalFile && auditFile.canonicalFile != inputWav.canonicalFile)
        require(!outputWav.exists() && !auditFile.exists()) { "Denoising outputs must be fresh" }
        val info = PcmWave.inspect(inputWav)
        require(info.sampleRate == 16000)
        var generated = 0L
        var written = 0L
        processor.reset()
        try {
            PcmWave.Writer(outputWav, 16000).use { writer ->
                fun append(samples: FloatArray) {
                    require(samples.all { it.isFinite() }) { "GTCRN emitted nonfinite samples" }
                    generated += samples.size
                    val keep = minOf(samples.size.toLong(), info.sampleCount - written).toInt()
                    if (keep > 0) {
                        writer.write(if (keep == samples.size) samples else samples.copyOf(keep))
                        written += keep
                    }
                }
                var offset = 0L
                while (offset < info.sampleCount) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(16000L, info.sampleCount - offset).toInt()
                    append(processor.run(PcmWave.readFloat(inputWav, offset, count)))
                    offset += count
                }
                currentCoroutineContext().ensureActive()
                append(processor.flush())
                if (written < info.sampleCount) writer.padSilence(info.sampleCount - written)
            }
            val actual = PcmWave.inspect(outputWav)
            check(actual.sampleCount == info.sampleCount) { "GTCRN output sample count was not restored" }
            val audit = DenoiseAudit(info.sampleCount, generated, actual.sampleCount,
                maxOf(0, info.sampleCount - generated), maxOf(0, generated - info.sampleCount))
            auditFile.parentFile?.mkdirs()
            auditFile.writeText(Json { encodeDefaults = true }.encodeToString(audit))
            return audit
        } catch (failure: Throwable) {
            outputWav.delete()
            auditFile.delete()
            throw failure
        } finally {
            processor.reset()
        }
    }

    override fun close() = processor.close()

    private class SherpaProcessor(model: File, threads: Int) : Processor {
        private val denoiser: OnlineSpeechDenoiser
        init {
            require(model.isFile && threads in 1..4)
            denoiser = OnlineSpeechDenoiser(config = OnlineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model.absolutePath),
                    numThreads = threads, provider = "cpu")))
            check(denoiser.sampleRate == 16000)
        }
        override fun run(samples: FloatArray): FloatArray = denoiser.run(samples, 16000).also {
            check(it.sampleRate == 16000)
        }.samples
        override fun flush(): FloatArray = denoiser.flush().also { check(it.sampleRate == 16000) }.samples
        override fun reset() = denoiser.reset()
        override fun close() = denoiser.release()
    }
}
