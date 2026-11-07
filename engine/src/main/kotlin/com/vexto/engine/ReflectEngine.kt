package com.vexto.engine

import android.content.Context
import android.net.Uri
import com.vexto.engine.audio.AudioDecoder
import com.vexto.engine.model.ProcessingStats
import com.vexto.engine.model.Stage
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.TranscriptionEvent
import com.vexto.engine.model.TranscriptionOptions
import com.vexto.engine.review.LocalConversationSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.function.BooleanSupplier
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

    /** Main dialogue is durable while optional emotion enrichment continues in the same job. */
    interface EnrichmentListener : DetailedListener {
        fun onDialogueReady(transcript: Transcript, artifactDirectory: String)
    }

    interface SummaryListener {
        /** Remaining time is unknown (−1) until the first model response. */
        fun onProgress(done: Int, total: Int, remainingMs: Long)
        fun onDone(summary: String)
        fun onError(error: Throwable)
    }

    interface SummaryWithTitleListener : SummaryListener {
        /** Optional title from the still-loaded model; null means the attempt failed. */
        fun onTitle(title: String?)
    }

    interface TitleListener {
        fun onDone(title: String)
        fun onBusy()
        fun onError(error: Throwable)
    }

    /** Запущенная локальная задача. После [cancel] колбэки больше не приходят. */
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

    @JvmStatic
    fun summarize(context: Context, transcript: String, listener: SummaryListener): Task =
        summarizeInternal(context, transcript, null, listener)

    /** The optional title gate runs on IO immediately before an extra request on the warm model. */
    @JvmStatic
    fun summarize(
        context: Context,
        transcript: String,
        generateTitle: BooleanSupplier,
        listener: SummaryWithTitleListener,
    ): Task = summarizeInternal(context, transcript, generateTitle, listener)

    private fun summarizeInternal(
        context: Context,
        transcript: String,
        generateTitle: BooleanSupplier?,
        listener: SummaryListener,
    ): Task = Task(scope.launch {
        try {
            withContext(Dispatchers.IO) {
                LocalConversationSummary(context.applicationContext).run(
                    transcript,
                    progress = { done, total, remaining ->
                        withContext(Dispatchers.Main) { listener.onProgress(done, total, remaining) }
                    },
                    onSummary = { summary -> withContext(Dispatchers.Main) { listener.onDone(summary) } },
                    generateTitle = generateTitle?.let { { it.asBoolean } },
                    onTitle = { title ->
                        withContext(Dispatchers.Main) { (listener as? SummaryWithTitleListener)?.onTitle(title) }
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            listener.onError(error)
        }
    })

    /** Title generation is opportunistic: never queue behind other model work. */
    @JvmStatic
    fun tryGenerateTitle(
        context: Context,
        transcript: String,
        eligible: BooleanSupplier,
        listener: TitleListener,
    ): Task = Task(scope.launch {
        try {
            val title = withContext(Dispatchers.IO) {
                LocalConversationSummary(context.applicationContext).tryTitle(transcript, eligible::getAsBoolean)
            }
            if (title == null) listener.onBusy() else listener.onDone(title)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            listener.onError(error)
        }
    })

    private fun listen(events: Flow<TranscriptionEvent>, listener: Listener): Task = Task(
        scope.launch {
            try {
                events.collect { event ->
                    when (event) {
                        is TranscriptionEvent.Progress -> listener.onProgress(event.stage, event.fraction)
                        is TranscriptionEvent.Partial -> listener.onPartial(event.transcript)
                        is TranscriptionEvent.CoreReady -> {
                            if (event.finalText && listener is EnrichmentListener)
                                listener.onDialogueReady(event.transcript, event.artifactDirectory)
                            else (listener as? DetailedListener)?.onCoreReady(event.transcript, event.artifactDirectory)
                        }
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
