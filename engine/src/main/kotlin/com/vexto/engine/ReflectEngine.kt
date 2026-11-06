package com.vexto.engine

import android.content.Context
import android.net.Uri
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

/** Фасад для Java-кода UI: колбэки приходят в главном потоке. */
object ReflectEngine {

    interface Listener {
        fun onProgress(stage: Stage, fraction: Float)

        /** Промежуточная расшифровка по уже распознанной части записи. */
        fun onPartial(transcript: Transcript)

        fun onDone(transcript: Transcript, stats: ProcessingStats)

        fun onError(error: Throwable)
    }

    /** Запущенная расшифровка. После [cancel] колбэки больше не приходят. */
    class Task internal constructor(private val job: Job) {
        fun cancel() = job.cancel()

        val isActive: Boolean get() = job.isActive
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @JvmStatic
    @JvmOverloads
    fun transcribe(
        context: Context,
        audio: Uri,
        options: TranscriptionOptions = TranscriptionOptions(),
        listener: Listener,
    ): Task = Task(
        scope.launch {
            try {
                TranscriptionEngine(context).transcribe(audio, options).collect { event ->
                    when (event) {
                        is TranscriptionEvent.Progress -> listener.onProgress(event.stage, event.fraction)
                        is TranscriptionEvent.Partial -> listener.onPartial(event.transcript)
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
