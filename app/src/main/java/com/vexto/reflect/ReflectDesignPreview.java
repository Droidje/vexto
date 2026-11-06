package com.vexto.reflect;

import java.util.Arrays;
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
}
