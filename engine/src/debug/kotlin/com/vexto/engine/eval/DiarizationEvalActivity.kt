package com.vexto.engine.eval

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.vexto.engine.audio.AudioDecoder
import com.vexto.engine.audio.PcmAudio
import com.vexto.engine.diarization.Diarizer
import com.vexto.engine.model.SampleRate
import com.vexto.engine.models.ModelStore
import com.vexto.engine.pipeline.VoiceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Debug-only raw diarization probe. Intent dir is an app-owned child of external files/diar-eval.
 * Empty input directories bootstrap ModelStore only. No ASR, transcript, reference or forced count.
 */
class DiarizationEvalActivity : Activity() {
    private val scope = MainScope()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(32, 96, 32, 32) }
        setContentView(status)
        scope.launch {
            try {
                val base = requireNotNull(getExternalFilesDir("diar-eval")).canonicalFile
                val supplied = intent.getStringExtra("dir") ?: "run-${System.currentTimeMillis()}"
                val requested = File(supplied)
                val dir = (if (requested.isAbsolute) requested else File(base, supplied)).canonicalFile
                require(dir != base && dir.toPath().startsWith(base.toPath())) { "dir must be below app diar-eval" }
                val threads = intent.getIntExtra("threads", 4).coerceIn(1, 4)
                withContext(Dispatchers.Default) { runAll(dir, threads) }
                status.text = "Diarization probe finished: ${dir.name}"
            } catch (error: Exception) {
                Log.e(Tag, "probe setup failed", error)
                status.text = "Diarization probe failed: ${error.javaClass.simpleName}"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runAll(dir: File, threads: Int) {
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create probe directory" }
        check(dir.listFiles().orEmpty().none {
            it.name == "_done" || it.name == "_error.json" || it.extension == "rttm" || it.name.endsWith(".stats.json")
        }) { "Refusing existing probe outputs; use a unique directory" }
        var activeStats: File? = null
        var stats = JSONObject().put("schema_version", 1).put("status", "running")
        try {
            val prepareStart = SystemClock.elapsedRealtime()
            val models = ModelStore.install(this) {}
            val prepareMs = SystemClock.elapsedRealtime() - prepareStart
            val manifestBytes = assets.open("models/manifest.json").use { it.readBytes() }
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            val inputs = dir.listFiles { file -> file.isFile && file.extension.equals("wav", true) }
                .orEmpty().sortedBy { it.name }
            for (input in inputs) {
                require(input.nameWithoutExtension.matches(Regex("[A-Za-z0-9._-]+"))) { "Use an RTTM-safe input basename" }
                activeStats = File(dir, "${input.nameWithoutExtension}.stats.json")
                stats = JSONObject().put("schema_version", 1).put("status", "running")
                    .put("audio_file", input.name).put("audio_sha256", sha256(input))
                    .put("forced_speaker_count", JSONObject.NULL).put("threads", threads)
                    .put("model_manifest", manifest).put("model_manifest_sha256", digest(manifestBytes))
                    .put("asr_executed", false).put("frame_ms", PcmAudio.FrameSize * 1000 / SampleRate)
                    .put("memory_note", "App process VmHWM, including runtime; not PSS or a memory hard cap")
                writeJson(activeStats, stats)
                val started = SystemClock.elapsedRealtime()
                val reset = runCatching { File("/proc/self/clear_refs").writeText("5"); true }.getOrDefault(false)
                stats.put("peak_reset_succeeded", reset)
                    .put("peak_scope", if (reset) "after model installation" else "process lifetime")
                val stages = JSONObject().put("prepare", prepareMs)
                val decodeStart = SystemClock.elapsedRealtime()
                AudioDecoder.decode(this, Uri.fromFile(input), cacheDir) {}.use { audio ->
                    stages.put("decode", SystemClock.elapsedRealtime() - decodeStart)
                    stats.put("audio_ms", audio.durationMs).put("audio_samples", audio.sampleCount)
                    val vadStart = SystemClock.elapsedRealtime()
                    val speech = VoiceDetector.segments(models, audio, VoiceDetector.Diarization) {}
                    stages.put("vad", SystemClock.elapsedRealtime() - vadStart)
                    stats.put("vad_segments", speech.size)
                        .put("vad_speech_seconds", speech.sumOf { it.end - it.start }.toDouble() / SampleRate)
                    val diarStart = SystemClock.elapsedRealtime()
                    val turns = Diarizer.run(models, audio, speech, speakerCount = null, threads = threads) {}
                    stages.put("diarization", SystemClock.elapsedRealtime() - diarStart)
                    val rttm = turns.joinToString("\n", postfix = if (turns.isEmpty()) "" else "\n") { turn ->
                        require(turn.startMs >= 0 && turn.endMs > turn.startMs && turn.endMs <= audio.durationMs)
                        String.format(Locale.ROOT, "SPEAKER %s 1 %.3f %.3f <NA> <NA> speaker_%d <NA> <NA>",
                            input.nameWithoutExtension, turn.startMs / 1000.0,
                            (turn.endMs - turn.startMs) / 1000.0, turn.speaker)
                    }
                    val rttmFile = File(dir, "${input.nameWithoutExtension}.rttm")
                    rttmFile.writeText(rttm, Charsets.UTF_8)
                    stats.put("hypothesis_rttm_sha256", sha256(rttmFile))
                    stats.put("turns", turns.size).put("speaker_count", turns.map { it.speaker }.distinct().size)
                }
                stats.put("stage_ms", stages).put("wall_ms", prepareMs + SystemClock.elapsedRealtime() - started)
                    .put("diarization_stage", "Production windowing, embedding, clustering, small-cluster absorption and turns")
                    .put("rss_bytes", procBytes("VmRSS:")).put("peak_rss_bytes", procBytes("VmHWM:"))
                    .put("status", "completed")
                writeJson(activeStats, stats)
                Log.i(Tag, "${input.name}: wall_ms=${stats.getLong("wall_ms")} peak_rss_bytes=${stats.getLong("peak_rss_bytes")}")
                activeStats = null
            }
            File(dir, "_done").writeText("ok ${inputs.size}\n", Charsets.UTF_8)
        } catch (error: Exception) {
            stats.put("status", "failed").put("error_type", error.javaClass.name).put("error", error.message)
            writeJson(activeStats ?: File(dir, "_error.json"), stats)
            File(dir, "_done").writeText("error\n", Charsets.UTF_8)
            Log.e(Tag, "diarization probe failed", error)
            throw error
        }
    }

    private fun writeJson(file: File, value: JSONObject) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(value.toString(2) + "\n", Charsets.UTF_8)
        check(temp.renameTo(file)) { "Cannot publish stats" }
    }

    private fun procBytes(key: String): Long = File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith(key) }?.trim()?.split(Regex("\\s+"))
            ?.getOrNull(1)?.toLongOrNull()?.times(1024) ?: 0
    }

    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hex(hash.digest())
    }

    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object { const val Tag = "VextoDiarEval" }
}
