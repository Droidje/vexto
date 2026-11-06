package com.vexto.engine.model

/** Этапы обработки в порядке выполнения. */
enum class Stage(val title: String) {
    Preparing("Готовлю модели"),
    Decoding("Декодирую аудио"),
    Diarization("Разделяю голоса"),
    Recognition("Распознаю речь"),
    Assembly("Собираю расшифровку"),
    Punctuation("Расставляю знаки"),
}

sealed interface TranscriptionEvent {
    /** [fraction] от 0 до 1 внутри этапа. */
    data class Progress(val stage: Stage, val fraction: Float) : TranscriptionEvent

    /** Реплики, готовые до завершения всей обработки. */
    data class Partial(val transcript: Transcript) : TranscriptionEvent

    data class Done(val transcript: Transcript, val stats: ProcessingStats) : TranscriptionEvent
}

data class ProcessingStats(
    val audioMs: Long,
    val processingMs: Long,
    val stageMs: Map<Stage, Long>,
    /** Пиковый RSS процесса (VmHWM), включает загруженные модели. */
    val peakMemoryMb: Long,
    val asrModel: String,
    val threads: Int,
) {
    /** Real-time factor: время обработки к длительности записи. */
    val rtf: Double get() = if (audioMs == 0L) 0.0 else processingMs.toDouble() / audioMs
}

data class TranscriptionOptions @JvmOverloads constructor(
    /** Число говорящих, если известно; null означает автоопределение. */
    val speakerCount: Int? = null,
    val punctuation: Boolean = true,
)

const val SampleRate = 16_000
