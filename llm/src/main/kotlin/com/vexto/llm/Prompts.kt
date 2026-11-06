package com.vexto.llm

/** Промпты и GBNF-грамматики трёх задач. Модель видит транскрипт только как текст для анализа. */
internal object Prompts {

    const val SYSTEM = "Ты аккуратный помощник, который анализирует стенограммы разговоров на русском языке. " +
        "Опирайся только на текст стенограммы и ничего не выдумывай."

    // Строка JSON без кавычек, обратной косой черты и управляющих символов: парсер проще, а вывод надёжнее.
    private const val JSON_TEXT = """[^"\\\x7F\x00-\x1F]"""

    fun title(transcript: String) = """
        |Придумай короткое название для этого разговора: 2-5 слов о его главной теме.
        |Пиши без кавычек и без точки в конце. Выведи только название.
        |
        |Стенограмма:
        |$transcript
    """.trimMargin()

    const val TITLE_GRAMMAR = """root ::= [^\n"«».]{3,60}"""

    // Модель лучше находит имена, когда просим список «имя → участник», а не таблицу по всем участникам:
    // во втором случае Qwen3-0.6B отвечает null для всех.
    fun speakerNames(transcript: String) = """
        |Стенограмма:
        |$transcript
        |
        |Какие имена людей звучат в этой стенограмме? Для каждого имени укажи метку участника, которого так зовут.
        |Ответь JSON-списком вида [{"name": "Имя", "speaker": "speaker_N"}]. Если имён нет, ответь [].
    """.trimMargin()

    /** Допускает обёртку ```json и переносы строк: без них модель под грамматикой сразу закрывает список. */
    fun speakerNamesGrammar(speakers: List<String>): String {
        val ids = speakers.joinToString(" | ") { "\"\\\"$it\\\"\"" }
        return """
            |root ::= ("```json" ws)? "[" ws (item ("," ws item){0,9})? ws "]" ws ("```")?
            |item ::= "{" ws "\"name\": \"" name "\"," ws "\"speaker\": " speaker ws "}"
            |speaker ::= $ids
            |name ::= word (" " word)?
            |word ::= [А-ЯЁA-Z] [а-яёa-zА-ЯЁA-Z-]{1,20}
            |ws ::= [ \n]{0,8}
        """.trimMargin()
    }

    fun summary(transcript: String) = """
        |Стенограмма:
        |$transcript
        |
        |Составь краткую сводку этого разговора.
        |summary: о чём разговор и что решили, 2-3 предложения своими словами.
        |points: до 5 главных решений и задач. Каждый пункт пиши своими словами коротко, например «Анна пришлёт отчёт к среде», и укажи в t время реплики из квадратных скобок, где это прозвучало.
        |Ответь JSON-объектом {"summary": "...", "points": [{"t": "мм:сс", "text": "..."}]}.
    """.trimMargin()

    /** Время в пунктах ограничено метками, которые есть в этом куске транскрипта. */
    fun summaryGrammar(timestamps: Collection<String>): String {
        val ts = timestamps.joinToString(" | ") { "\"$it\"" }
        return """
            |root ::= "{\"summary\": \"" text "\", \"points\": [" (point (", " point){0,4})? "]}"
            |point ::= "{\"t\": \"" ts "\", \"text\": \"" text "\"}"
            |ts ::= $ts
            |text ::= $JSON_TEXT{3,400}
        """.trimMargin()
    }

    fun mergeSummaries(parts: List<String>) = """
        |Ниже краткие сводки последовательных частей одного разговора.
        |Объедини их в одну сводку из 2-4 предложений. Выведи только текст сводки.
        |
        |${parts.mapIndexed { i, p -> "Часть ${i + 1}: $p" }.joinToString("\n")}
    """.trimMargin()

    val MERGE_GRAMMAR = """root ::= $JSON_TEXT{10,700}"""
}
