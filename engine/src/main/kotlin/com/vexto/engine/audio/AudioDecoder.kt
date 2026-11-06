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

/**
 * Декодирует любой поддержанный Android аудиоформат (wav, m4a/aac, mp3, ogg/opus, flac, amr)
 * в моно PCM16 16 кГц во временном файле. Память не зависит от длины записи.
 */
internal object AudioDecoder {

    private const val TimeoutUs = 10_000L

    suspend fun decode(context: Context, uri: Uri, cacheDir: File, onProgress: (Float) -> Unit): PcmAudio {
        val extractor = MediaExtractor()
        val file = File.createTempFile("decoded", ".pcm", cacheDir)
        val writer = PcmWriter(file)
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var output = OutputFormat.of(format)
            var resampler = Resampler(output.sampleRate, SampleRate)
            var mono = FloatArray(0)
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TimeoutUs)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TimeoutUs)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = OutputFormat.of(codec.outputFormat)
                        if (changed.sampleRate != output.sampleRate) {
                            resampler.flush(writer::write)
                            resampler = Resampler(changed.sampleRate, SampleRate)
                        }
                        output = changed
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val frames = info.size / output.bytesPerFrame
                        if (mono.size < frames) mono = FloatArray(frames)
                        output.downmix(buffer, frames, mono)
                        resampler.process(mono, frames, writer::write)
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            resampler.flush(writer::write)
            onProgress(1f)
            return writer.finish()
        } catch (e: Throwable) {
            writer.close()
            file.delete()
            throw e
        } finally {
            codec?.stop()
            codec?.release()
            extractor.release()
        }
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
