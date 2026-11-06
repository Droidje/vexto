# engine: локальный quality profile

По умолчанию включена вся связка: **GTCRN → Nemotron 3 F32 → GigaAM v3 E2E RNNT → Whisper Large v3 Q5_0 → Qwen3-1.7B Q4_K_M → GigaAM-Emo v1**. Whisper и GigaAM равноправны: Qwen выбирает один целый исходный вариант для каждого общего фрагмента. Саммаризации и свободной генерации исправленного текста нет.

## Подготовка и сборка

Требуются JDK17, SDK35/BuildTools35.0.0, NDKr28c, CMake3.22.1+ и Ninja. Веса и native binaries не хранятся в Git. APK содержит код, лицензии и проверяемый manifest; **3 336 832 988 байта весов** устанавливаются отдельно, одним экземпляром в app-scoped storage.

```sh
bash scripts/fetch_models.sh --aar-only
python scripts/prepare_emotion_runtime.py
# Подготовленные pinned native sources, затем обе ABI; инструкции и source pins:
# tools/native/README.md; scripts/build_native_runtime.py --help
python scripts/prepare_quality_models.py --source-dir <готовые-модели> --download
bash ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Один раз запустите установленное приложение, затем:
python scripts/install_quality_pack.py --serial <device> --pack-dir .agents/work/quality-pack/quality-508faa2fac36
```

F32 Nemotron собирается из оригинального pinned `.nemo` через `scripts/convert_nemotron_fullprecision.py`; его нельзя скачать по выдуманной ссылке или восстановить из Q8. См. [рецепт конвертации](../docs/research/nemotron-android.md). `--sources-json` provisioner принимает локальный JSON `model_id → filename`, который следует хранить в `.agents/local/`. Все файлы проверяются по размерам и SHA256 до установки и при подготовке сессии. Сам инференс работает без сети.

`-PvextoNdkPath=<path>` и `-PvextoCmakeVersion=<version>` позволяют использовать существующие инструменты без общих машинных путей. `-PvextoSkipNative=true` предназначен для JVM-тестов, а не поставки приложения. Обычная сборка останавливается, если отсутствуют обязательные native CLI для выбранных ABI.

## Поведение

Каждая тяжёлая стадия работает в свежем `:inference` процессе. После ответа клиент ждёт смерти конкретного Binder и только затем запускает следующую модель. Native CLI берутся исключительно из installer-extracted `nativeLibraryDir`; загруженные исполняемые файлы не запускаются. Поддерживаются ARM64 и x86_64; факт сборки ARM64 не равен запуску на телефоне.

Исходный mono PCM16/16k WAV сохраняется. GTCRN делает отдельную ASR-копию и восстанавливает её число отсчётов; `alignmentVerified=false` явно означает, что акустическая задержка не проверена. Nemotron и Emo получают оригинал. Общий план фрагментов до10с использует тихие границы, сохраняет каждый отсчёт ровно один раз и даёт обоим ASR одинаковый WAV/SHA. Непрерывная речь на границе может пострадать; перекрывающиеся ASR-окна пока не реализованы.

Qwen возвращает только `select_asr_variant(chunk_id, source)`. Ядро проверяет schema, identity и SHA, затем копирует байты сохранённого кандидата. Оба оригинала, native tokens/timestamps и модельный manifest остаются в `filesDir/inference/jobs/<id>`. Решения имеют статусы `SELECTED_BY_LLM`, `ONE_AVAILABLE`, `UNRESOLVED`. При последнем GigaAM является только техническим вариантом отображения; это не победа в качестве, оба результата сохранены. Способность Qwen выбирать более точный текст без аудио ещё не доказана.

Базовый полный GigaAM-текст сохраняется до Whisper/LLM. Финальный текст заменяет только собственную неизменённую базовую копию; импорт и ручные изменения сохраняют приоритет. Приложение остаётся полностью локальным. Эмоции сохраняются в отдельном `emotion.json` и в вероятностях соответствующей реплики, не меняют текст и не являются достоверным определением чувств человека. Автоматического удаления архивов jobs пока нет: их жизненный цикл следует связать с удалением записи до длительного продуктового использования.

Наблюдаемый суммарный RSS UI+worker+native child плюс резерв128MiB ограничивается watchdog бюджетом2 000 000 000 байт. ОС Android исключена. Sampling100мс не является жёсткой гарантией для кратких всплесков аллокатора. При отказе сохраняются готовые оригиналы и явные предупреждения; модели не подменяются незаметно более лёгкими.

## API и проверки

Публичный `ReflectEngine.Listener` и экспорт `Transcript` сохранены. `DetailedListener.onCoreReady` позволяет сохранить ранний полный результат. В `ProcessingStats` добавлены `artifactDirectory`, `warnings`, `complete` (покрытие фрагментов), `fullProfileCompleted`, `selectionResolved`. Параметры `punctuation`/`speakerCount` оставлены ради совместимости: текст больше не переписывается пунктуатором, Nemotron считает спикеров автоматически (неподдержанный фиксированный count отмечается предупреждением).

```sh
bash scripts/fetch_models.sh --aar-only
bash ./gradlew -PvextoSkipNative=true :engine:testDebugUnitTest
python -m unittest discover -s tools/benchmarks/tests -v
```

Debug-only `QualityEvalActivity`: положите короткий WAV в `<app-external-files>/eval/input.wav`, запустите `adb shell am start -n com.vexto.reflect/com.vexto.engine.eval.QualityEvalActivity`; результаты появятся в `quality-eval/`, подробные артефакты — в приватном job. `--es mode native-help` проверяет упаковку CLI. Для сравнения точности используются фрагменты20–40с, а не длинные бенчмарки.

[Архитектурное решение](../docs/architecture/quality-engine.md), [исторические замеры старого движка](../docs/research/legacy-engine.md), [исследования моделей](../docs/research/pipeline-2gb.md).

Отдельный этап ERes2Net вычисляет голосовые признаки из оригинального аудио и интервалов Nemotron. Android сравнивает только признаки с совпадающими checkpoint, версией обработки и размерностью. При уверенном совпадении профили используют один глобальный UUID. Явное назначение пользователя сохраняется при замене промежуточного текста финальным. [Локальные профили и правило сопоставления](../docs/local-architecture.md).
