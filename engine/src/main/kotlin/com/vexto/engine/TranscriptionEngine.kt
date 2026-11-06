package com.vexto.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.vexto.engine.audio.AudioDecoder
import com.vexto.engine.diarization.Diarizer
import com.vexto.engine.model.ProcessingStats
import com.vexto.engine.model.SimplePunctuation
import com.vexto.engine.model.Stage
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.TranscriptAssembler
import com.vexto.engine.model.TranscriptionEvent
import com.vexto.engine.model.TranscriptionOptions
import com.vexto.engine.model.Word
import com.vexto.engine.models.ModelStore
import com.vexto.engine.pipeline.SpeechRecognizer
import com.vexto.engine.pipeline.VoiceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Офлайн-расшифровка: декодирование, диаризация всей записи, VAD, ASR по кускам,
 * сопоставление слов и спикеров. Перед VAD, эмбеддингами и ASR работает медленная АРУ. Модели грузятся по одной и освобождаются после своего
 * этапа, чтобы уложиться в 4 ГБ RAM. Одновременно идёт не больше одной расшифровки.
 */
class TranscriptionEngine(context: Context) {

    private val context = context.applicationContext

    fun transcribe(audio: Uri, options: TranscriptionOptions = TranscriptionOptions()): Flow<TranscriptionEvent> =
        channelFlow { lock.withLock { run(audio, options) } }.flowOn(Dispatchers.Default)

    private suspend fun ProducerScope<TranscriptionEvent>.run(uri: Uri, options: TranscriptionOptions) {
        val stageMs = linkedMapOf<Stage, Long>()
        val started = SystemClock.elapsedRealtime()
        MemoryProbe.resetPeak()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MaxThreads)

        suspend fun <T> stage(stage: Stage, block: suspend ((Float) -> Unit) -> T): T {
            val t0 = SystemClock.elapsedRealtime()
            var last = -1f
            val progress = { f: Float ->
                // Не чаще одного события на процент, иначе декодер заваливает канал
                if (f - last >= 0.01f || f >= 1f) {
                    last = f
                    trySend(TranscriptionEvent.Progress(stage, f))
                }
            }
            progress(0f)
            val result = block(progress)
            progress(1f)
            stageMs[stage] = SystemClock.elapsedRealtime() - t0
            return result
        }

        val models = stage(Stage.Preparing) { ModelStore.install(context, it) }
        val cacheDir = File(context.cacheDir, "engine").apply { mkdirs() }
        stage(Stage.Decoding) { AudioDecoder.decode(context, uri, cacheDir, it) }.use { audio ->
            val turns = stage(Stage.Diarization) { progress ->
                val speech = VoiceDetector.segments(models, audio, VoiceDetector.Diarization) { progress(it * VadShare) }
                Diarizer.run(models, audio, speech, options.speakerCount, threads) { progress(VadShare + (1 - VadShare) * it) }
            }
            val assembler = TranscriptAssembler()
            val words = stage(Stage.Recognition) { progress ->
                val segments = VoiceDetector.segments(models, audio, VoiceDetector.Recognition) { progress(it * VadShare) }
                val words = mutableListOf<Word>()
                SpeechRecognizer(models, threads).use { recognizer ->
                    var lastPartial = SystemClock.elapsedRealtime()
                    segments.forEachIndexed { i, segment ->
                        ensureActive()
                        words += recognizer.recognize(audio, segment)
                        progress(VadShare + (1 - VadShare) * (i + 1) / segments.size)
                        if (SystemClock.elapsedRealtime() - lastPartial > PartialIntervalMs) {
                            send(TranscriptionEvent.Partial(assembler.assemble(words, turns)))
                            lastPartial = SystemClock.elapsedRealtime()
                        }
                    }
                }
                words
            }
            var transcript: Transcript = stage(Stage.Assembly) { assembler.assemble(words, turns) }
            if (options.punctuation && !models.manifest.asr.punctuation) {
                transcript = stage(Stage.Punctuation) { SimplePunctuation.apply(transcript) }
            }
            val stats = ProcessingStats(
                audioMs = audio.durationMs,
                processingMs = SystemClock.elapsedRealtime() - started,
                stageMs = stageMs,
                peakMemoryMb = MemoryProbe.peakRssMb(),
                asrModel = models.manifest.asr.id,
                threads = threads,
            )
            send(TranscriptionEvent.Done(transcript, stats))
        }
    }

    private companion object {
        const val MaxThreads = 4
        const val VadShare = 0.1f
        const val PartialIntervalMs = 2_000L
        val lock = Mutex()
    }
}
