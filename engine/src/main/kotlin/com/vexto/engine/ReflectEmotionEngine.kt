package com.vexto.engine

import android.content.Context
import android.net.Uri
import com.vexto.engine.audio.AudioDecoder
import com.vexto.engine.emotion.GigaAmMel
import com.vexto.engine.emotion.GigaAmOrt
import com.vexto.engine.emotion.UtteranceEmotions
import com.vexto.engine.model.EmotionProbabilities
import com.vexto.engine.model.Utterance
import com.vexto.engine.models.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Local, preemptible emotion batch. All resources close before foreground inference is admitted. */
object ReflectEmotionEngine {
    interface Listener {
        /**
         * BACKGROUND worker callback. Persist the completed line before returning true.
         * Index is in the supplied list. Null means the audio interval has no samples.
         * Return false to discard a stale batch; no terminal callback is then delivered.
         */
        fun onUtterance(index: Int, scores: EmotionProbabilities?): Boolean
        /** Main-thread terminal callbacks, after releasing model and decoded PCM. */
        fun onDone()
        fun onYielded()
        fun onError(error: Throwable)
    }

    class Task internal constructor(private val job: Job) {
        /** Native inference stops only at a cooperative boundary; a callback may already be in flight. */
        fun cancel() = job.cancel()
        val isActive: Boolean get() = job.isActive
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @JvmStatic
    fun analyze(context: Context, audio: Uri, utterances: List<Utterance>, listener: Listener): Task {
        val appContext = context.applicationContext
        val lines = utterances.toList()
        return Task(scope.launch {
            try {
                var stale = false
                val completed = withContext(Dispatchers.Default) {
                    sharedPriorityInferenceGate.background {
                        if (lines.all { it.emotionProbabilities != null }) return@background
                        val models = ModelStore.install(appContext) {}
                        val model = models.manifest.emotion ?: throw IOException("В APK нет модели эмоций")
                        currentCoroutineContext().ensureActive()
                        val cache = File(appContext.cacheDir, "engine").apply { mkdirs() }
                        AudioDecoder.decode(appContext, audio, cache) {}.use { pcm ->
                            currentCoroutineContext().ensureActive()
                            GigaAmOrt(models.path(model), 2).use { session ->
                                val frontend = GigaAmMel()
                                for ((index, line) in lines.withIndex()) {
                                    currentCoroutineContext().ensureActive()
                                    if (line.emotionProbabilities != null) continue
                                    val scores = UtteranceEmotions.predict(session, frontend, pcm, line)
                                    currentCoroutineContext().ensureActive()
                                    if (!listener.onUtterance(index, scores)) {
                                        stale = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
                if (!stale) {
                    if (completed) listener.onDone() else listener.onYielded()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                listener.onError(e)
            }
        })
    }
}
