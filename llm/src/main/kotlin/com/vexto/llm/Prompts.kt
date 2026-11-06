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

    fun speakerNames(transcript: String, speakers: List<String>): String {
        val example = speakers.joinToString(", ") { "\"$it\": null" }
        return """
            |В стенограмме участники обозначены метками ${speakers.joinToString(", ")}.
            |Найди имена участников, которые прямо прозвучали в разговоре:
            |- участник представился сам: «меня зовут Анна», «я Олег» — это имя говорящего;
            |- к участнику обратились по имени: «Анна, что скажешь?» — это имя того, кто отвечает следующим.
            |Если имя участника не прозвучало, оставь null. Не придумывай имена.
            |
            |Пример.
            |[00:01] speaker_1: Добрый день, меня зовут Ольга.
            |[00:05] speaker_2: Здравствуйте. Ольга, начнём с бюджета?
            |[00:09] speaker_3: Игорь, ты подготовил цифры?
            |[00:12] speaker_2: Да, подготовил.
            |Ответ: {"speaker_1": "Ольга", "speaker_2": "Игорь", "speaker_3": null}
            |
            |Стенограмма:
            |$transcript
            |
            |Ответь JSON-объектом вида {$example}, где вместо null может стоять имя в кавычках.
        """.trimMargin()
    }

    fun speakerNamesGrammar(speakers: List<String>): String {
        val items = speakers.joinToString(" \", \" ") { "\"\\\"$it\\\": \" value" }
        return """
            |root ::= "{" $items "}"
            |value ::= "null" | "\"" name "\""
            |name ::= word (" " word)?
            |word ::= [А-ЯЁA-Z] [а-яёa-zА-ЯЁA-Z-]{1,20}
        """.trimMargin()
    }

    fun summary(transcript: String) = """
        |Составь краткую сводку разговора по стенограмме.
        |Ответь JSON-объектом:
        |{"summary": "2-3 предложения о сути разговора", "points": [{"t": "мм:сс", "text": "ключевой момент, решение или договорённость"}]}
        |В поле t укажи время из квадратных скобок той реплики, где прозвучал этот момент.
        |Не больше 5 пунктов, каждый пункт одним коротким предложением.
        |
        |Стенограмма:
        |$transcript
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
