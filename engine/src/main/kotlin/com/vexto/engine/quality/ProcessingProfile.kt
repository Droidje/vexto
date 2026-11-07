package com.vexto.engine.quality

import com.vexto.engine.model.ProcessingMode
import kotlinx.serialization.Serializable
import java.io.File

/** Immutable for the lifetime of a recording, including every stage and retry. */
@Serializable
data class ProcessingProfile(
    val requestedMode: ProcessingMode,
    val mode: ProcessingMode,
    val denoise: Boolean,
    val gigaWorkers: Int,
    val whisperWorkers: Int,
    val gigaThreads: Int,
    val whisperThreads: Int,
) {
    val fast: Boolean get() = mode == ProcessingMode.FAST
    val whisperModelId: String get() = if (fast) "whisper_fast" else "whisper"
    val speakerAnnotations: Boolean get() = !fast

    fun expectedStages(slavic: Boolean): Set<WorkerStage> = WorkerStage.entries.toSet() - setOf(WorkerStage.GIGAAM_REFINE, WorkerStage.WHISPER_REFINE) -
        (if (denoise) emptySet() else setOf(WorkerStage.DENOISE)) -
        (if (speakerAnnotations) emptySet() else setOf(WorkerStage.EMOTION)) -
        (if (slavic) emptySet() else setOf(WorkerStage.GIGAAM))

    fun validate() {
        require(mode != ProcessingMode.AUTO)
        require(gigaWorkers in 1..2 && whisperWorkers in 1..2)
        require(gigaThreads in 1..4 && whisperThreads in 1..4)
        require(gigaWorkers * gigaThreads <= 4 && whisperWorkers * whisperThreads <= 4)
        require(fast || whisperWorkers == 1) { "Large Whisper contexts cannot share the 2GB budget" }
        require(denoise == !fast)
    }

    companion object {
        fun resolve(requested: ProcessingMode, audioMs: Long): ProcessingProfile {
            require(audioMs >= 0)
            val mode = requested.resolve(audioMs)
            val fast = mode == ProcessingMode.FAST
            return ProcessingProfile(requested, mode, !fast, 2, if (fast) 2 else 1,
                2, if (fast) 2 else 4).also { it.validate() }
        }

        fun read(job: File): ProcessingProfile {
            val file = File(job, "processing-profile.json")
            return if (file.isFile) JobFiles.load<ProcessingProfile>(file).also { it.validate() }
                else resolve(ProcessingMode.QUALITY, 0) // Old request producers retain their original profile.
        }
    }
}
