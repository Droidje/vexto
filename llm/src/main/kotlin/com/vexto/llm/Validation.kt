package com.vexto.llm

import org.json.JSONObject

/** Разбор и проверка ответов модели: всё, что нельзя подтвердить транскриптом, отбрасывается. */
internal object Validation {

    fun title(raw: String): String? {
        val t = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '"', '\'', '«', '»', '.', ':', '-')
        return t.takeIf { it.length >= 3 }?.let { it.substring(0, 1).uppercase() + it.substring(1) }
    }

    // Слова, которые маленькая модель путает с именами: приветствия и обращения к группе.
    private val notNames = setOf(
        "привет", "здравствуйте", "здравствуй", "добрый", "спасибо", "пожалуйста", "пока", "слушай",
        "слушайте", "смотри", "смотрите", "ну", "да", "нет", "ладно", "хорошо", "окей", "итак", "давай",
        "давайте", "коллеги", "ребята", "друзья", "господа", "все", "мы", "я", "ты", "вы", "он", "она",
        "спикер", "speaker", "null",
    )

    /**
     * Оставляет имя, только если оно буквально звучит в тексте транскрипта
     * (с учётом окончаний: «Мурзик» засчитывается по «Мурзика»). Одно имя не отдаётся двум участникам.
     */
    fun speakerNames(raw: String, speakers: List<String>, transcriptText: String): Map<String, String?> {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val used = mutableSetOf<String>()
        val result = LinkedHashMap<String, String?>()
        for (id in speakers) {
            val name = json?.optString(id, "")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            result[id] = name?.takeIf { isNameInText(it, transcriptText) && used.add(it.lowercase()) }
        }
        return result
    }

    fun isNameInText(name: String, text: String): Boolean {
        val words = name.split(' ')
        if (words.any { it.length < 2 || it.lowercase() in notNames }) return false
        val pattern = words.joinToString("""\s+""") { Regex.escape(it) }
        return Regex("""(?<!\p{L})$pattern""", setOf(RegexOption.IGNORE_CASE)).containsMatchIn(text)
    }

    /** [timestamps] — метка `mm:ss` → начало первой реплики с этой меткой. */
    fun summary(raw: String, timestamps: Map<String, Long>): Summary? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val text = json.optString("summary").trim()
        val points = mutableListOf<SummaryPoint>()
        val arr = json.optJSONArray("points")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val t = p.optString("t")
                val startMs = timestamps[t] ?: continue
                val pointText = p.optString("text").trim()
                if (pointText.isEmpty() || points.any { it.text.equals(pointText, ignoreCase = true) }) continue
                points += SummaryPoint(t, startMs, pointText)
            }
        }
        if (text.isEmpty() && points.isEmpty()) return null
        return Summary(text, points.sortedBy { it.startMs })
    }

    fun timestamps(utterances: List<Utterance>): Map<String, Long> {
        val map = LinkedHashMap<String, Long>()
        for (u in utterances) map.putIfAbsent(TranscriptText.timestamp(u.startMs), u.startMs)
        return map
    }
}
