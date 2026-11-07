package com.vexto.engine.pipeline

import kotlinx.serialization.Serializable

/** One language for the whole recording. Russian and Belarusian keep the current ASR chain. */
@Serializable
data class RecordingLanguage(
    val code: String,
    val route: String,
    val samples: Int,
    val detected: Boolean,
) {
    init {
        require(route == SLAVIC || route == WHISPER_ONLY)
        require(code.matches(CODE)) { "invalid_language_code" }
        require(samples >= 0)
        if (route == WHISPER_ONLY) require(code != "ru" && code != "be")
        if (detected && route == SLAVIC) require(code == "ru" || code == "be")
    }

    /** Whisper.cpp flag. Belarusian stays on the existing Russian flag. */
    val whisperLanguage: String get() = if (route == SLAVIC) "ru" else code

    companion object {
        const val SLAVIC = "slavic"
        const val WHISPER_ONLY = "whisper_only"
        const val WINDOW_SAMPLES = 30 * 16_000
        private val CODE = Regex("[a-z]{2,3}")

        fun fromDetection(code: String, samples: Int): RecordingLanguage {
            val normalized = code.trim().lowercase()
            require(normalized.matches(CODE)) { "invalid_language_code" }
            val route = if (normalized == "ru" || normalized == "be") SLAVIC else WHISPER_ONLY
            return RecordingLanguage(normalized, route, samples, detected = true)
        }

        /** Detection failed. The recording keeps the Russian pipeline. */
        fun undetected() = RecordingLanguage("ru", SLAVIC, 0, detected = false)
    }
}
