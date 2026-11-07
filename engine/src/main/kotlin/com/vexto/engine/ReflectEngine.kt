package com.vexto.engine

import android.content.Context
import android.net.Uri
import com.vexto.engine.audio.AudioDecoder
import com.vexto.engine.model.ProcessingStats
import com.vexto.engine.model.Stage
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.TranscriptionEvent
import com.vexto.engine.model.TranscriptionOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.function.Consumer

/** Фасад для Java-кода UI: колбэки приходят в главном потоке. */
object ReflectEngine {

    interface Listener {
        fun onProgress(stage: Stage, fraction: Float)

        /** Промежуточная расшифровка по уже распознанной части записи. */
        fun onPartial(transcript: Transcript)

        fun onDone(transcript: Transcript, stats: ProcessingStats)

        fun onError(error: Throwable)
    }

    interface DetailedListener : Listener {
        fun onCoreReady(transcript: Transcript, artifactDirectory: String)
    }

    /** Запущенная расшифровка. После [cancel] колбэки больше не приходят. */
    class Task internal constructor(private val job: Job) {
        fun cancel() = job.cancel()

        val isActive: Boolean get() = job.isActive
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Preview only: decode bounded audio off the UI thread; an empty envelope means progress-only. */
    @JvmStatic
    fun previewWaveform(context: Context, audio: Uri, durationMs: Int, result: Consumer<IntArray>): Task =
        Task(scope.launch {
            val bars = withContext(Dispatchers.IO) {
                if (durationMs !in 1..AudioDecoder.PreviewLimitMs) intArrayOf()
                else try {
                    AudioDecoder.previewWaveform(context.applicationContext, audio)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    intArrayOf()
                }
            }
            result.accept(bars)
        })

    @JvmStatic
    @JvmOverloads
    fun transcribe(
        context: Context,
        audio: Uri,
        options: TranscriptionOptions = TranscriptionOptions(),
        listener: Listener,
    ): Task = listen(TranscriptionEngine(context).transcribe(audio, options), listener)

    /** Reopens the same private checkpoint directory when called again with the same job ID. */
    @JvmStatic
    fun transcribeResumable(
        context: Context,
        audio: Uri,
        options: TranscriptionOptions,
        jobId: String,
        listener: DetailedListener,
    ): Task = listen(TranscriptionEngine(context).transcribeResumable(audio, options, jobId), listener)

    private fun listen(events: Flow<TranscriptionEvent>, listener: Listener): Task = Task(
        scope.launch {
            try {
                events.collect { event ->
                    when (event) {
                        is TranscriptionEvent.Progress -> listener.onProgress(event.stage, event.fraction)
                        is TranscriptionEvent.Partial -> listener.onPartial(event.transcript)
                        is TranscriptionEvent.CoreReady -> (listener as? DetailedListener)?.onCoreReady(event.transcript, event.artifactDirectory)
                        is TranscriptionEvent.Done -> listener.onDone(event.transcript, event.stats)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                listener.onError(e)
            }
        },
    )
}
