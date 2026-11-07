package com.vexto.engine.eval

import android.app.Activity
import android.os.Bundle
import com.vexto.engine.audio.PcmWave
import com.vexto.engine.model.Speaker
import com.vexto.engine.model.Transcript
import com.vexto.engine.model.Utterance
import com.vexto.engine.model.export.TranscriptExport
import com.vexto.engine.models.OverlapModelStore
import com.vexto.engine.pipeline.UtteranceOverlapAnnotations
import com.vexto.engine.quality.*
import kotlinx.coroutines.*
import java.io.File

/** Runs the production isolated worker on at most forty seconds of public test audio. */
class OverlapEvalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val run = intent.getStringExtra("run") ?: "probe"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        scope.launch {
            val out = File(getExternalFilesDir(null), "overlap-eval").apply { mkdirs() }
            try {
                File(out, "_done").delete()
                File(out, "error.txt").delete()
                val input = File(out, "input.wav")
                val info = PcmWave.inspect(input)
                require(info.sampleCount in 64000..640000)
                val manifest = requireNotNull(OverlapModelStore.manifest(this@OverlapEvalActivity))
                val job = File(filesDir, "inference/jobs/overlap-evaluation-$run").apply { mkdirs() }
                require(!File(job, "original.wav").exists()) { "Use a fresh evaluation job" }
                input.copyTo(File(job, "original.wav"))
                val count = (info.sampleCount / 64000).toInt()
                // Labels identify windows, not a claimed ASR transcript. Flags come only from inference.
                val transcript = Transcript((0 until count).map {
                    Utterance("SPEAKER_00", it * 4000L, (it + 1) * 4000L, "Контрольный аудиофрагмент ${it + 1}")
                }, listOf(Speaker("SPEAKER_00")))
                JobFiles.save(File(job, "overlap-model.json"), manifest)
                JobFiles.save(File(job, "overlap-targets.json"), UtteranceOverlapAnnotations.targets(transcript))
                val result = StageClient(this@OverlapEvalActivity).run(
                    WorkerRequest(WorkerStage.OVERLAP, job.absolutePath, "original.wav"))
                JobFiles.save(File(out, "result.json"), result)
                check(result.completed && result.error == null) { "Overlap worker failed: ${result.error}" }
                val annotated = UtteranceOverlapAnnotations.attach(transcript, result.overlaps, manifest.revision)
                JobFiles.write(File(out, "transcript.metadata.json"), TranscriptExport.jsonWithMetadata(annotated).toByteArray())
                JobFiles.write(File(out, "_done"), byteArrayOf(1))
            } catch (error: Throwable) {
                JobFiles.write(File(out, "error.txt"), error.stackTraceToString().toByteArray())
            } finally { withContext(Dispatchers.Main) { finish() } }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
