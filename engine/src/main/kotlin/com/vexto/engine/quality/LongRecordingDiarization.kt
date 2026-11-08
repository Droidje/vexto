package com.vexto.engine.quality

import com.vexto.engine.diarization.DiarizationTimeline
import com.vexto.engine.runtime.NativeDiarizationProvider
import com.vexto.engine.runtime.NativeDiarizationResult
import com.vexto.engine.runtime.NativeProcessResult
import com.vexto.engine.runtime.NativeProcessRunner
import com.vexto.engine.runtime.NativeSpeakerTurn
import java.io.File

internal data class DiarizationOutput(val result: NativeDiarizationResult, val warnings: List<String>)

/** Same Nemotron model. Recordings longer than five minutes do not spend it on long pauses. */
internal object LongRecordingDiarization {
    suspend fun run(model: File, input: File, work: File, runner: NativeProcessRunner, threads: Int = 4,
                    onStable: ((Long, List<NativeSpeakerTurn>) -> Unit)? = null): DiarizationOutput {
        val plan = DiarizationTimeline.plan(input)
        JobFiles.save(File(work, "timeline.json"), mapOf(
            "sampleCount" to plan.sampleCount.toString(),
            "compacted" to plan.compacted.toString(),
            "omittedSamples" to plan.omittedSamples.toString(),
            "kept" to plan.kept.joinToString(",") { "${it.fromSample}-${it.toSample}" },
        ))
        val warning = if (!plan.compacted) emptyList() else listOf(
            "DIARIZATION_COMPACT:kept_ms=${plan.sampleCount * 1000 / 16000 - plan.omittedSamples * 1000 / 16000}:omitted_ms=${plan.omittedSamples * 1000 / 16000}")
        if (!plan.compacted) {
            return DiarizationOutput(NativeDiarizationProvider.run(model, input, File(work, "turns.rttm"), work, runner, threads, onStable), warning)
        }
        if (plan.kept.isEmpty()) {
            JobFiles.write(File(work, "turns.rttm"), ByteArray(0))
            return DiarizationOutput(NativeDiarizationResult(emptyList(), NativeProcessResult(0, 0)), warning)
        }
        val compact = File(work, "compact.wav")
        DiarizationTimeline.writeCompact(input, compact, plan)
        val mappedStable = onStable?.let { listener -> { frontier: Long, rows: List<NativeSpeakerTurn> ->
            val mapped = DiarizationTimeline.remap(plan, rows.map {
                DiarizationTimeline.SpeakerInterval(it.speakerId, it.startMs, it.endMs)
            }).map { NativeSpeakerTurn(it.speakerId, it.startMs, it.endMs) }
            var consumed = 0L
            var original = 0L
            for (range in plan.kept) {
                val length = range.toSample - range.fromSample
                val count = (frontier * 16 - consumed).coerceIn(0, length)
                original = range.fromSample + count
                if (count < length) break
                consumed += length
            }
            listener(original / 16, mapped)
        } }
        val native = NativeDiarizationProvider.run(model, compact, File(work, "compact.rttm"), work, runner, threads, mappedStable)
        val restored = DiarizationTimeline.remap(plan, native.turns.map {
            DiarizationTimeline.SpeakerInterval(it.speakerId, it.startMs, it.endMs)
        }).map { NativeSpeakerTurn(it.speakerId, it.startMs, it.endMs) }
        JobFiles.write(File(work, "turns.rttm"), restored.joinToString("") { turn ->
            val start = turn.startMs / 1000.0
            val duration = (turn.endMs - turn.startMs) / 1000.0
            "SPEAKER recording 1 %.3f %.3f <NA> <NA> %s <NA> <NA>\n".format(start, duration, turn.speakerId)
        }.toByteArray())
        return DiarizationOutput(NativeDiarizationResult(restored, native.process), warning)
    }
}
