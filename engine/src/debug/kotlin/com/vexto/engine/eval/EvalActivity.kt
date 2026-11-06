package com.vexto.engine.eval

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.vexto.engine.TranscriptionEngine
import com.vexto.engine.model.ProcessingStats
import com.vexto.engine.model.TranscriptionEvent
import com.vexto.engine.model.TranscriptionOptions
import com.vexto.engine.model.export.TranscriptExport
import com.vexto.engine.models.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Прогон движка на всех аудиофайлах каталога: для `<name>.wav` пишет `<name>.json` в формате
 * заказчика и `<name>.stats.json` со временем и памятью. По окончании создаёт `_done`.
 *
 * Параметры intent: `dir` (по умолчанию external files/eval), `speakers` (число спикеров).
 */
class EvalActivity : Activity() {

    private val scope: CoroutineScope = MainScope()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(32, 96, 32, 32) }
        setContentView(status)
        val dir = intent.getStringExtra("dir")?.let(::File) ?: File(getExternalFilesDir(null), "eval")
        val speakers = intent.getIntExtra("speakers", 0).takeIf { it > 0 }
        scope.launch { runAll(dir, speakers) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runAll(dir: File, speakers: Int?) {
        val done = File(dir, "_done").apply { delete() }
        try {
            show("Готовлю модели")
            withContext(Dispatchers.IO) { ModelStore.install(this@EvalActivity) {} }
            val inputs = dir.listFiles { f -> f.isFile && f.extension.lowercase() in AudioExtensions }
                .orEmpty().sortedBy { it.name }
            val engine = TranscriptionEngine(this)
            for (input in inputs) {
                show("${input.name}: старт")
                engine.transcribe(Uri.fromFile(input), TranscriptionOptions(speakerCount = speakers)).collect { event ->
                    when (event) {
                        is TranscriptionEvent.Progress -> show("${input.name}: ${event.stage.title} ${(event.fraction * 100).toInt()}%")
                        is TranscriptionEvent.Partial -> Unit
                        is TranscriptionEvent.Done -> withContext(Dispatchers.IO) {
                            File(dir, "${input.nameWithoutExtension}.json").writeText(TranscriptExport.json(event.transcript))
                            File(dir, "${input.nameWithoutExtension}.stats.json").writeText(statsJson(event.stats))
                            Log.i(Tag, "${input.name}: rtf=${"%.3f".format(event.stats.rtf)} peak=${event.stats.peakMemoryMb} MB ${event.stats.stageMs}")
                        }
                    }
                }
            }
            done.writeText("ok ${inputs.size}\n")
            show("Готово: ${inputs.size} файлов")
        } catch (e: Exception) {
            Log.e(Tag, "eval failed", e)
            done.writeText("error ${e}\n")
            show("Ошибка: $e")
        }
    }

    private fun show(text: String) {
        status.text = text
    }

    private fun statsJson(stats: ProcessingStats): String = buildJsonObject {
        put("audio_ms", stats.audioMs)
        put("processing_ms", stats.processingMs)
        put("rtf", stats.rtf)
        put("peak_rss_mb", stats.peakMemoryMb)
        put("asr_model", stats.asrModel)
        put("threads", stats.threads)
        putJsonObject("stage_ms") { stats.stageMs.forEach { (stage, ms) -> put(stage.name.lowercase(), ms) } }
    }.toString()

    private companion object {
        const val Tag = "VextoEval"
        val AudioExtensions = setOf("wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "amr", "3gp", "webm")
    }
}
