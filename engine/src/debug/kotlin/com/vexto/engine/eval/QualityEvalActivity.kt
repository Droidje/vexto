package com.vexto.engine.eval

import android.app.Activity
import android.os.Bundle
import android.net.Uri
import com.vexto.engine.TranscriptionEngine
import com.vexto.engine.model.*
import com.vexto.engine.quality.JobFiles
import com.vexto.engine.runtime.*
import kotlinx.coroutines.*
import java.io.File

/** Debug-only SDK probe. Reads only eval/input.wav under this APK's private external directory. */
class QualityEvalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val mode = intent.getStringExtra("mode") ?: "pipeline"
        val run = intent.getStringExtra("run")
        require(run == null || run.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")))
        val inputName = intent.getStringExtra("input") ?: "input.wav"
        require(inputName.matches(Regex("input\\.[a-z0-9]{1,8}")))
        scope.launch {
            val out = File(getExternalFilesDir(null), "quality-eval" + (run?.let { "/$it" } ?: "")).apply { mkdirs() }
            try {
                if (mode == "native-help") {
                    val results = listOf("nemotron", "whisper", "llama").associateWith { name ->
                        try {
                            NativeProcessRunner(this@QualityEvalActivity).run("libvexto_${name}_exec.so", listOf("--help"),
                                out, File(out, "$name.stdout"), File(out, "$name.stderr"), 15000)
                            "executed_exit_0"
                        } catch (e: NativeProcessException) { "${e.reason}:${e.exitCode}" }
                    }
                    JobFiles.save(File(out, "native-help.json"), results)
                } else {
                    val audio = if (run == null) File(getExternalFilesDir(null), "eval/$inputName") else File(out, inputName)
                    val media = android.media.MediaMetadataRetriever()
                    try {
                        media.setDataSource(audio.absolutePath)
                        val duration = media.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        require(duration != null && duration <= 400_000) { "Use an audio fixture of at most 6 minutes 40 seconds" }
                    } finally { media.release() }
                    val processingMode = ProcessingMode.valueOf(intent.getStringExtra("processing_mode") ?: "QUALITY")
                    val started = android.os.SystemClock.elapsedRealtime()
                    TranscriptionEngine(this@QualityEvalActivity).transcribe(Uri.fromFile(audio),
                        TranscriptionOptions(processingMode = processingMode)).collect { event ->
                        when (event) {
                            is TranscriptionEvent.Progress -> JobFiles.save(File(out, "progress.json"),
                                mapOf("stage" to event.stage.name, "fraction" to event.fraction.toString()))
                            is TranscriptionEvent.CoreReady -> {
                                JobFiles.write(File(out, "core-ready"), event.artifactDirectory.toByteArray())
                                if (event.finalText) JobFiles.save(File(out, "dialogue-ready.json"), mapOf(
                                    "elapsed_ms" to (android.os.SystemClock.elapsedRealtime() - started).toString(),
                                    "job" to event.artifactDirectory))
                            }
                            is TranscriptionEvent.Partial -> Unit
                            is TranscriptionEvent.Done -> {
                                val job = File(event.stats.artifactDirectory)
                                for (name in listOf("transcript.json", "transcript.metadata.json", "completion.json")) {
                                    JobFiles.write(File(out, name), File(job, name).readBytes())
                                }
                                JobFiles.save(File(out, "done.json"), mapOf("job" to event.stats.artifactDirectory,
                                    "peak_mb" to event.stats.peakMemoryMb.toString(), "warnings" to event.stats.warnings.joinToString("\n"),
                                    "full_profile_completed" to event.stats.fullProfileCompleted.toString(),
                                    "selection_resolved" to event.stats.selectionResolved.toString(), "complete" to event.stats.complete.toString()))
                            }
                        }
                    }
                }
                JobFiles.write(File(out, "_done"), mode.toByteArray())
            } catch (e: Throwable) {
                JobFiles.write(File(out, "error.txt"), e.stackTraceToString().toByteArray())
            } finally { withContext(Dispatchers.Main) { finish() } }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
