package com.vexto.llm

import org.json.JSONArray
import org.json.JSONObject

/** Разбор и проверка ответов модели: всё, что нельзя подтвердить транскриптом, отбрасывается. */
internal object Validation {

    fun title(raw: String): String? {
        val t = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '"', '\'', '«', '»', '.', ':', '-')
        return t.takeIf { it.length >= 3 }?.let { it.substring(0, 1).uppercase() + it.substring(1) }
    }

    // Слова, которые маленькая модель путает с именами: приветствия, обращения к группе, шаблон из промпта.
    private val notNames = setOf(
        "привет", "здравствуйте", "здравствуй", "добрый", "спасибо", "пожалуйста", "пока", "слушай",
        "слушайте", "смотри", "смотрите", "ну", "да", "нет", "ладно", "хорошо", "окей", "итак", "давай",
        "давайте", "коллеги", "ребята", "друзья", "господа", "все", "мы", "я", "ты", "вы", "он", "она",
        "имя", "спикер", "speaker", "null",
    )

    // Слова перед именем, по которым видно, что человек называет себя.
    private val selfIntro = Regex("""(?<!\p{L})(?:зовут|я|это|меня)[\s,—-]+$""", RegexOption.IGNORE_CASE)

    /**
     * Разбирает список `[{"name", "speaker"}]` от модели и оставляет только подтверждённые имена:
     * - имя буквально звучит в репликах (окончание допускается: «Барсик» засчитывается по «Барсику»);
     * - участник представился («меня зовут», «я», «это» перед именем) или имя произносят только другие;
     *   если участник сам говорит это имя без представления, он обращается к кому-то другому;
     * - у участника одно имя, одно имя (с учётом окончаний) у одного участника.
     */
    fun speakerNames(raw: String, utterances: List<Utterance>): Map<String, String?> {
        val result = LinkedHashMap<String, String?>()
        utterances.forEach { result.putIfAbsent(it.speakerId, null) }
        val json = raw.substringAfter('[', "").substringBeforeLast(']', "")
        val items = runCatching { JSONArray("[$json]") }.getOrNull() ?: return result
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val speaker = item.optString("speaker")
            if (speaker !in result || result[speaker] != null) continue
            if (result.values.any { it != null && sameName(it, name) }) continue
            if (isConfirmed(name, speaker, utterances)) result[speaker] = name
        }
        return result
    }

    private fun isConfirmed(name: String, speaker: String, utterances: List<Utterance>): Boolean {
        val pattern = namePattern(name) ?: return false
        var byOthers = false
        var introduced = false
        var ownMention = false
        for (u in utterances) {
            for (m in pattern.findAll(u.text)) {
                when {
                    u.speakerId != speaker -> byOthers = true
                    selfIntro.containsMatchIn(u.text.substring(0, m.range.first)) -> introduced = true
                    else -> ownMention = true
                }
            }
        }
        return introduced || (byOthers && !ownMention)
    }

    fun isNameInText(name: String, text: String): Boolean = namePattern(name)?.containsMatchIn(text) == true

    private fun namePattern(name: String): Regex? {
        val words = name.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.any { it.length < 2 || it.lowercase() in notNames }) return null
        val body = words.joinToString("""\s+""") { Regex.escape(it) }
        return Regex("""(?<!\p{L})$body""", RegexOption.IGNORE_CASE)
    }

    private fun sameName(a: String, b: String): Boolean {
        val x = a.lowercase()
        val y = b.lowercase()
        return x.startsWith(y) || y.startsWith(x)
    }

    private val speakerPrefix = Regex("""^\s*speaker_\d+:\s*""", RegexOption.IGNORE_CASE)

    /** [timestamps]: метка `mm:ss` → начало первой реплики с этой меткой. */
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
                // Модель иногда копирует реплику вместе с меткой: «Speaker_2: ...».
                val pointText = p.optString("text").replace(speakerPrefix, "").trim()
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
