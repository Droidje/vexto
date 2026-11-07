package com.vexto.engine.quality

/** Whole-stage deadlines scale with recording size; per-child native deadlines remain tighter. */
internal object StageTimeoutPolicy {
    fun milliseconds(stage: WorkerStage, audioMs: Long, chunks: Int, speakers: Int = 1): Long {
        require(audioMs >= 0 && chunks >= 0 && speakers >= 0)
        return when (stage) {
            WorkerStage.WHISPER, WorkerStage.WHISPER_REFINE -> 60_000L + maxOf(1, chunks).toLong() * 650_000L
            WorkerStage.SELECT -> 60_000L + maxOf(1, chunks).toLong() * 150_000L
            WorkerStage.DENOISE, WorkerStage.GIGAAM, WorkerStage.GIGAAM_REFINE, WorkerStage.VOICEPRINT, WorkerStage.EMOTION, WorkerStage.OVERLAP ->
                120_000L + audioMs.coerceAtMost(Long.MAX_VALUE / 10) * 5
            WorkerStage.DIARIZE -> 1_860_000L
        }
    }
}
