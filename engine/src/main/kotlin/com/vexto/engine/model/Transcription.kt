package com.vexto.engine.model

/** Этапы обработки в порядке выполнения. */
enum class Stage(val title: String) {
    Preparing("Готовлю модели"),
    Decoding("Декодирую аудио"),
    Denoising("Убираю шум"),
    Diarization("Разделяю голоса"),
    Language("Определяю язык"),
    Voiceprints("Сохраняю голоса"),
    Recognition("Распознаю речь"),
    Comparison("Распознаю Whisper"),
    Selection("Сравниваю варианты"),
    Assembly("Собираю расшифровку"),
    Punctuation("Расставляю знаки"),
    Emotion("Определяю эмоции реплик"),
}

sealed interface TranscriptionEvent {
    /** [fraction] от 0 до 1 внутри этапа. */
    data class Progress(val stage: Stage, val fraction: Float) : TranscriptionEvent

    /** Реплики, готовые до завершения всей обработки. */
    data class Partial(val transcript: Transcript) : TranscriptionEvent

    data class CoreReady(val transcript: Transcript, val artifactDirectory: String) : TranscriptionEvent

    data class Done(val transcript: Transcript, val stats: ProcessingStats) : TranscriptionEvent
}

data class ProcessingStats(
    val audioMs: Long,
    val processingMs: Long,
    val stageMs: Map<Stage, Long>,
    /** Максимум наблюдаемой суммы RSS UI, worker и native children; резерв хранится отдельно. */
    val peakMemoryMb: Long,
    val asrModel: String,
    val threads: Int,
    val artifactDirectory: String = "",
    val warnings: List<String> = emptyList(),
    val complete: Boolean = true,
    val fullProfileCompleted: Boolean = true,
    val selectionResolved: Boolean = true,
) {
    /** Real-time factor: время обработки к длительности записи. */
    val rtf: Double get() = if (audioMs == 0L) 0.0 else processingMs.toDouble() / audioMs
}

data class TranscriptionOptions @JvmOverloads constructor(
    /** Число говорящих, если известно; null означает автоопределение. */
    val speakerCount: Int? = null,
    /** Legacy API flag. Selection-only profile always preserves recognizer punctuation verbatim. */
    val punctuation: Boolean = true,
)

const val SampleRate = 16_000
