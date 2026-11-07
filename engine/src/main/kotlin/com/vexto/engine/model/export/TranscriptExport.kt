package com.vexto.engine.model.export

import com.vexto.engine.model.PhoneticSurface
import com.vexto.engine.model.Transcript
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String, val title: String) {
    Json("json", "application/json", "JSON"),
    Text("txt", "text/plain", "Текст"),
    Markdown("md", "text/markdown", "Markdown"),
    Srt("srt", "application/x-subrip", "Субтитры SRT"),
}

object TranscriptExport {

    @JvmStatic
    fun render(transcript: Transcript, format: ExportFormat): String = when (format) {
        ExportFormat.Json -> submissionJson(transcript)
        ExportFormat.Text -> text(transcript)
        ExportFormat.Markdown -> markdown(transcript)
        ExportFormat.Srt -> srt(transcript)
    }

    /** JSON по образцу ТЗ. Текст реплики остаётся сохранённым, включая фонетические знаки. */
    @JvmStatic
    fun json(transcript: Transcript): String = encode(transcript, includeMetadata = false, present = false)

    /** Тот же контракт для файла, который видит пользователь: фонетические знаки заменены базовой записью. */
    @JvmStatic
    fun submissionJson(transcript: Transcript): String = encode(transcript, includeMetadata = false, present = true)

    /** Внутренний расширенный слой: эмоции сохраняются отдельно от контракта заказчика. */
    @JvmStatic
    fun jsonWithMetadata(transcript: Transcript): String = encode(transcript, includeMetadata = true, present = false)

    private fun encode(transcript: Transcript, includeMetadata: Boolean, present: Boolean): String {
        val rows = transcript.utterances.map {
            JsonUtterance(it.speakerId, it.startMs, it.endMs,
                if (present) PhoneticSurface.present(it.text) else it.text,
                if (includeMetadata) it.emotionProbabilities?.ordered() else null,
                if (includeMetadata) transcript.speaker(it.speakerId).name else null)
        }
        validate(rows)
        return JsonFormat.encodeToString(JsonTranscript.serializer(), JsonTranscript(rows))
    }

    /** Читает строгие и прежние расширенные файлы, сохраняя исходные строки без нормализации. */
    @JvmStatic
    fun parseJson(json: String): List<JsonUtterance> {
        val rows = JsonFormat.parseToJsonElement(json).jsonObject.getValue("transcript").jsonArray
        rows.forEach { element ->
            val row = element.jsonObject
            for (key in listOf("start_ms", "end_ms")) {
                val number = row.getValue(key).jsonPrimitive
                require(!number.isString && number.longOrNull != null) { "Expected integer milliseconds" }
            }
            require(row.getValue("speaker_id").jsonPrimitive.isString && row.getValue("text").jsonPrimitive.isString)
        }
        return JsonFormat.decodeFromString(JsonTranscript.serializer(), json).transcript.also(::validate)
    }

    private fun validate(rows: List<JsonUtterance>) {
        var previous = 0L
        rows.forEach {
            require(it.speakerId.isNotBlank()) { "Expected a speaker identifier" }
            require(it.startMs >= previous && it.endMs > it.startMs) { "Expected nonnegative ordered time ranges" }
            previous = it.startMs // Simultaneous and overlapping speakers remain valid.
        }
    }

    @JvmStatic
    fun text(transcript: Transcript): String = transcript.utterances.joinToString("\n\n") {
        "[${clock(it.startMs)}] ${transcript.speaker(it.speakerId).displayName()}: ${PhoneticSurface.present(it.text)}"
    }

    @JvmStatic
    fun markdown(transcript: Transcript): String = buildString {
        append("| Время | Участник | Реплика |\n|---|---|---|\n")
        transcript.utterances.forEach {
            append("| ${clock(it.startMs)}–${clock(it.endMs)} | ${transcript.speaker(it.speakerId).displayName()} | ")
            append(PhoneticSurface.present(it.text).replace("|", "\\|")).append(" |\n")
        }
    }

    @JvmStatic
    fun srt(transcript: Transcript): String = transcript.utterances.mapIndexed { i, it ->
        "${i + 1}\n${srtTime(it.startMs)} --> ${srtTime(it.endMs)}\n" +
            "${transcript.speaker(it.speakerId).displayName()}: ${PhoneticSurface.present(it.text)}\n"
    }.joinToString("\n")

    @JvmStatic
    fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, m, s)
    }

    private fun srtTime(ms: Long): String =
        "%02d:%02d:%02d,%03d".format(Locale.ROOT, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private val JsonFormat = Json {
        encodeDefaults = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}

@Serializable
data class JsonTranscript(val transcript: List<JsonUtterance>)

@Serializable
data class JsonUtterance(
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
    @SerialName("emotion_probabilities") val emotionProbabilities: List<Double>? = null,
    @SerialName("speaker_name") val speakerName: String? = null,
)
