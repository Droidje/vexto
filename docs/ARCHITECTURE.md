# Архитектура решения

Оглавление. Подробные описания лежат в [architecture/](architecture/) и
[local-architecture.md](local-architecture.md).

## Контекст

Android-приложение для офлайн-расшифровки разговоров с разделением спикеров,
таймкодами и экспортом в JSON, Markdown, TXT и SRT. Требования заказчика:
[hackathon-2026-11.md](hackathon-2026-11.md), продуктовые решения:
[product.md](product.md).

## Компоненты

| каталог | роль |
|---|---|
| `app/` | интерфейс Android (экраны макета Reflect, запись, плеер, экспорт) |
| `engine/` | офлайн-движок: подготовка звука, диаризация, ASR, знаки препинания, эмоции, локальная LLM |
| `tools/` | сборка нативного рантайма, подготовка моделей, замеры, проверка схемы |
| `scripts/` | сборка и проверка APK, подготовка и проверка моделей |

## Основные технические решения

- Режимы обработки «Быстрый» и «Качественный»:
  [adaptive-audio-core.md](architecture/adaptive-audio-core.md),
  [queued-audio-modes.md](architecture/queued-audio-modes.md).
- Подготовка звука: [audio-preparation.md](architecture/audio-preparation.md).
- Качественный конвейер: [quality-pipeline.md](architecture/quality-pipeline.md),
  [quality-engine.md](architecture/quality-engine.md).
- Быстрый конвейер: [fast-pipeline.md](architecture/fast-pipeline.md).
- Совместимость Nemotron: [nemotron-cli-compatibility.md](architecture/nemotron-cli-compatibility.md).
- Имена спикеров и контекст: [context-speaker-names.md](architecture/context-speaker-names.md).
- Выбор моделей: [research/](research/).

## Зависимости

JDK 17, Android SDK 35, NDK 28.2, CMake 3.22; модели проверяются по SHA-256,
список и лицензии в [tools/model-notices/](../tools/model-notices/).

## Безопасность и приватность

Аудио, текст и голосовые эмбеддинги хранятся на устройстве. Аккаунта, API и
сервера нет, весь инференс идёт без интернета. Узнавание человека между
записями использует локальные профили и не отправляет данные наружу.
