package com.vexto.reflect;

import com.vexto.engine.model.EmotionProbabilities;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Example content from Figma, used only by the opt-in debug preview. */
final class ReflectDesignPreview {
    private ReflectDesignPreview() {}

    static List<ReflectCatalog.Recording> recordings() {
        List<String> first = Arrays.asList("record_speaker_first", "record_speaker_second", "record_speaker_third");
        List<String> second = Arrays.asList("record_speaker_first", "record_speaker_third");
        return Arrays.asList(
                new ReflectCatalog.Recording(null, "Запись №12", "Сегодня", "14:32", "15 минут",
                        "Выполнено", true, false, first, "Jennifer Colein Алексей Иванов встреча"),
                new ReflectCatalog.Recording(null, "Запись №7", "Сегодня", "12:24", "158 минут",
                        "Ожидание", false, true, second, "Jennifer Colein обсуждение"),
                new ReflectCatalog.Recording(null, "Запись №8", "Сегодня", "8:53", "13 минут",
                        "Выполнено", true, false, first, "Jennifer Colein Алексей Иванов интервью"));
    }

    static List<ReflectCatalog.Speaker> speakers() {
        return Arrays.asList(
                new ReflectCatalog.Speaker("jennifer-colein", "Jennifer Colein", "157 минут", "speaker_jennifer"),
                new ReflectCatalog.Speaker("alexey-ivanov", "Алексей Иванов", "80 минут", "speaker_alexey"),
                new ReflectCatalog.Speaker("mikhail-gvozdev", "Михаил Гвоздев", "42 минут", "speaker_mikhail"));
    }

    static List<ReflectCatalog.Conversation> conversations() {
        var sprint = new ReflectCatalog.Conversation("Спринт тайп", "3:57 PM",
                "Алекс: Здорово! Буду рада, если поделишься какими-то советами.", false, null);
        var room = new ReflectCatalog.Conversation("Переговорная 2", "1:34 PM",
                "Настя: Все они показывают разные аспекты моих навыков.", true, null);
        return Arrays.asList(sprint, room, room, sprint);
    }

    static String transcriptTitle(String key) {
        return "Спринт тайп".equals(key) ? "Спринт по портфолио" : key;
    }

    static ReflectTranscript transcript(String key) {
        if ("Запись №7".equals(key)) return null;
        if ("Переговорная 2".equals(key)) {
            return new ReflectTranscript(Collections.singletonList(new ReflectTranscript.Line(
                    "Настя", 0, 4500, "Все они показывают разные аспекты моих навыков.")));
        }
        String question = "Звучит интересно! Какие проекты ты выбрала для своего спринта?";
        String plan = "Планирую создать небольшую презентацию с описанием каждого проекта, добавить графики и отзывы клиентов. Думаю, это поможет лучше донести информацию.";
        return new ReflectTranscript(Arrays.asList(
                new ReflectTranscript.Line("Александр Симаранов", 1000, 5000, question,
                        new EmotionProbabilities(0.80, 0.10, 0.05, 0.05)),
                new ReflectTranscript.Line("Настя Иванова", 5000, 17000,
                        "Ну, у меня есть несколько ключевых: разработка мобильного приложения, участие в большом маркетинговом проекте и запуск веб-сайта. Все они показывают разные аспекты моих навыков.",
                        new EmotionProbabilities(0.10, 0.80, 0.05, 0.05)),
                new ReflectTranscript.Line("Олег Шишкин", 17000, 25000, plan,
                        new EmotionProbabilities(0.10, 0.05, 0.80, 0.05)),
                new ReflectTranscript.Line("Олег Шишкин", 17000, 25000, plan,
                        new EmotionProbabilities(0.10, 0.05, 0.05, 0.80)),
                new ReflectTranscript.Line("Александр Симаранов", 25000, 29000, question),
                new ReflectTranscript.Line("Александр Симаранов", 29000, 33000, question)));
    }
}
