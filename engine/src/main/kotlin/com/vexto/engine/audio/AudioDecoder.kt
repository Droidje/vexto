package com.vexto.engine.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.vexto.engine.model.SampleRate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

class AudioInputTooLongException : IOException("Decoded audio exceeds the sample limit")

/**
 * Декодирует любой поддержанный Android аудиоформат (wav, m4a/aac, mp3, ogg/opus, flac, amr)
 * в моно PCM16 16 кГц во временном файле. Память не зависит от длины записи.
 */
internal object AudioDecoder {

    private const val TimeoutUs = 10_000L
    const val PreviewLimitMs = 120_000
    private const val MaxAudioSeconds = 4L * 60 * 60

    suspend fun decode(context: Context, uri: Uri, cacheDir: File, onProgress: (Float) -> Unit): PcmAudio =
        decode(context, uri, cacheDir, MaxAudioSeconds * SampleRate, onProgress)

    suspend fun previewWaveform(context: Context, uri: Uri): IntArray =
        decode(context, uri, context.cacheDir, PreviewLimitMs.toLong() * SampleRate / 1000) {}
            .use { it.previewWaveform() }

    /** Write the canonical model input directly; callers atomically publish this pending file. */
    suspend fun decodeToWave(context: Context, uri: Uri, output: File, onProgress: (Float) -> Unit): PcmWave.Info {
        val source = uri.takeIf { it.scheme == "file" }?.path?.let(::File)
        require(source?.canonicalFile != output.canonicalFile) { "Decode output must not replace the source" }
        val progress = DecodeProgress(onProgress)
        val maxSamples = MaxAudioSeconds * SampleRate
        try {
            if (source != null) {
                CanonicalWaveDecoder.copyIfCompatible(source, output, maxSamples, progress::report)?.let {
                    progress.finish()
                    return it
                }
            }
            DecodedWaveWriter(output).use { writer ->
                decodeInto(context, uri, maxSamples, writer, progress::report)
                return writer.finish().also { progress.finish() }
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private suspend fun decode(context: Context, uri: Uri, cacheDir: File, maxSamples: Long,
                               onProgress: (Float) -> Unit): PcmAudio {
        val file = File.createTempFile("decoded", ".pcm", cacheDir)
        val writer = PcmWriter(file)
        try {
            val progress = DecodeProgress(onProgress)
            decodeInto(context, uri, maxSamples, writer, progress::report)
            return writer.finish().also { progress.finish() }
        } catch (failure: Throwable) {
            writer.close()
            file.delete()
            throw failure
        }
    }

    private suspend fun decodeInto(context: Context, uri: Uri, maxSamples: Long, writer: DecodedSampleSink,
                                   onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        val write: (FloatArray, Int) -> Unit = { samples, count ->
            if (count > maxSamples - writer.sampleCount) throw AudioInputTooLongException()
            writer.write(samples, count)
        }
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var output = OutputFormat.of(format)
            var resampler: Resampler? = null
            var mono = FloatArray(0)
            var inputDone = false
            decoding@ while (true) {
                currentCoroutineContext().ensureActive()
                var supplied = false
                // Feed available inputs before waiting for output; cap the burst for cancellation/fairness.
                for (batch in 0 until 8) {
                    if (inputDone) break
                    val index = decoder.dequeueInputBuffer(0)
                    if (index < 0) break
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                        extractor.advance()
                    }
                    supplied = true
                }
                // Drain every ready output. Block only when we supplied no input, avoiding a busy poll loop.
                var waitUs = if (supplied) 0L else TimeoutUs
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val index = decoder.dequeueOutputBuffer(info, waitUs)
                    waitUs = 0L
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val changed = OutputFormat.of(decoder.outputFormat)
                            if (changed.sampleRate != output.sampleRate) {
                                resampler?.flush(write)
                                resampler = null
                            }
                            output = changed
                        }
                        index >= 0 -> {
                            var frames = 0
                            var direct = false
                            try {
                                if (info.size > 0) {
                                    val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    frames = info.size / output.bytesPerFrame
                                    if (output.sampleRate == SampleRate && output.channels == 1 &&
                                        output.encoding == AudioFormat.ENCODING_PCM_16BIT) {
                                        if (frames > maxSamples - writer.sampleCount) throw AudioInputTooLongException()
                                        direct = writer.writePcm16(buffer, frames)
                                    }
                                    if (!direct) {
                                        if (mono.size < frames) mono = FloatArray(frames)
                                        output.downmix(buffer, frames, mono)
                                    }
                                }
                            } finally {
                                // The expensive FIR loop owns mono, not the codec buffer.
                                decoder.releaseOutputBuffer(index, false)
                            }
                            if (!direct && frames > 0) {
                                val active = resampler ?: Resampler(output.sampleRate, SampleRate).also { resampler = it }
                                active.process(mono, frames, write)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@decoding
                        }
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        else -> break
                    }
                }
            }
            resampler?.flush(write)
        } finally {
            try { codec?.stop() }
            finally { try { codec?.release() } finally { extractor.release() } }
        }
    }

    private class DecodeProgress(private val emit: (Float) -> Unit) {
        private var last = Long.MIN_VALUE
        fun report(fraction: Float) {
            val now = System.nanoTime()
            if (last == Long.MIN_VALUE || now - last >= 100_000_000L) {
                last = now
                emit(fraction.coerceIn(0f, 0.99f))
            }
        }
        fun finish() = emit(1f)
    }

    private class OutputFormat(val sampleRate: Int, val channels: Int, val encoding: Int) {
        val bytesPerFrame = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2

        /** Среднее по каналам. */
        fun downmix(buffer: java.nio.ByteBuffer, frames: Int, out: FloatArray) {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val floats = buffer.asFloatBuffer()
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += floats.get()
                    out[f] = sum / channels
                }
            } else {
                val shorts = buffer.asShortBuffer()
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += shorts.get()
                    out[f] = sum / (32768f * channels)
                }
            }
        }

        companion object {
            fun of(format: MediaFormat) = OutputFormat(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                },
            )
        }
    }
}
