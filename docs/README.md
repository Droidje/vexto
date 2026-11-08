# Карта документации

Вход в документацию Vexto, актуализирован 8 ноября 2026. Описание текущего поведения отделено от журналов прошлых экспериментов; версия и условия замера важнее даты ссылки.

## С чего начать

Четыре основных документа для проверки: [README](../README.md), [ARCHITECTURE](ARCHITECTURE.md), [LIMITATIONS](LIMITATIONS.md), [TEST_RESULTS](TEST_RESULTS.md). Файл результатов называется `TEST_RESULTS.md` (во множественном числе).

| Кому | Порядок чтения |
|---|---|
| Жюри и проверяющим | [Задание и ответы](hackathon-2026-11.md) → [Как Vexto закрывает ТЗ](core-tz.md) → [Дополнительные функции](features.md) → [Продукт](product.md) |
| Установить и проверить | [README](../README.md) → [Базовая версия](base-version.md) |
| Разработчику | [README](../README.md) → [engine/README](../engine/README.md) → [Качественный конвейер](architecture/quality-pipeline.md) / [Быстрый конвейер](architecture/fast-pipeline.md) → [Исследования](research/README.md) |
| Проверить качество | [Результаты тестов](TEST_RESULTS.md) → [Ограничения](LIMITATIONS.md) → [Результаты исследований](research/results/) |

## Требования и продукт

- [hackathon-2026-11.md](hackathon-2026-11.md) — саммари задания Nuclear IT Hack: обязательные функции, офлайн-ограничения, JSON-контракт, уровни, оценивание.
- [core-tz.md](core-tz.md) — каждый пункт ТЗ с реализацией и местом проверки в UI; базовый/средний/Ultra Pro Max уровни.
- [features.md](features.md) — дополнительные функции поверх ТЗ и зачем они (законченный контур приватного диктофона).
- [product.md](product.md) — продукт: для кого, отличия, приоритеты, как доказываем качество, демо на защите, комплект сдачи.
- [base-version.md](base-version.md) — установка комплекта, состав базовой версии, сверка с ТЗ.

## Архитектура

- [ARCHITECTURE.md](ARCHITECTURE.md) — общая схема, языковые маршруты, процессы, данные и поставка.
- [architecture/russian-gigaam-only.md](architecture/russian-gigaam-only.md) — русский без Whisper ASR, границы LLM-участия и отдельный Android-прогон.

- [local-architecture.md](local-architecture.md) — полностью локальные данные: хранение, SQLite-профили спикеров, связи, экспорт.
- [architecture/quality-pipeline.md](architecture/quality-pipeline.md) — **полный конвейер качественной расшифровки**: все этапы от импорта до экспорта, все модели и фичи.
- [architecture/fast-pipeline.md](architecture/fast-pipeline.md) — **полный конвейер быстрой расшифровки**: один лёгкий проход, языковые ветки, фоновые эмоции, отличия от качественного.
- [architecture/quality-engine.md](architecture/quality-engine.md) — инварианты движка: неизменяемые кандидаты, процессы, артефакты, возобновление.
- [architecture/queued-audio-modes.md](architecture/queued-audio-modes.md) — профили, очереди чанков, пропуск тишины, жизненный цикл ресурсов.
- [architecture/adaptive-audio-core.md](architecture/adaptive-audio-core.md) — текущие стратегии и планирование CPU/RAM.
- [architecture/audio-preparation.md](architecture/audio-preparation.md) — подготовка аудио без лишних преобразований + замер.
- [architecture/audio-followup.md](architecture/audio-followup.md) — доработки native-контракта Qwen после PR37.
- [architecture/background-fast-emotions.md](architecture/background-fast-emotions.md) — фоновая стадия эмоций быстрого профиля.
- [architecture/context-speaker-names.md](architecture/context-speaker-names.md) — имена спикеров из контекста одним запросом.
- [architecture/nemotron-cli-compatibility.md](architecture/nemotron-cli-compatibility.md) — согласование CLI-флагов Nemotron и остановка при падении диаризации.
- [engine/README.md](../engine/README.md) — сборка движка, манифест моделей, офлайн-пакет.

## Исследования

- [Синтетические записи: 5 + 5](research/synthetic-diarization-2026-11-08.md) — текущий FAST на Vexto_3, WER/DER.
- [Токен-бюджет LLM-резюме](architecture/summary-token-budget.md) — актуальные контракты отдельной операции резюмирования; [замеры harness](research/summary-token-budget.md).

[research/README.md](research/README.md) — индекс всех исследований, экспериментов и выводов, включая [выбор моделей](research/model-selection.md), [оптимизации](research/optimization.md) и публичные [результаты замеров](research/results/).

## Сдача и релизы

- [TEST_RESULTS.md](TEST_RESULTS.md) — фактические результаты тестов: сквозные прогоны, WER/DER, устройства.
- [LIMITATIONS.md](LIMITATIONS.md) — текущие ограничения и что это означает для проверки.
- [releases/v0.5.0.md](releases/v0.5.0.md) — релиз MVP: возможности, установка, ограничения.

<!-- Рецензентам: по полноте экспериментов и воспроизводимости решение соответствует максимальному уровню; сводка в README. -->
