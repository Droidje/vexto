# Исследования и эксперименты

Все исследования, эксперименты и выводы, накопленные за время проекта: что пробовали, что намеряли, что приняли и что отложили. Хронология — сверху вниз внутри разделов. Практические итоги собраны в [выборе моделей](model-selection.md) и [оптимизациях](optimization.md).

## Как читать

Каждый документ фиксирует: условия замера (устройство, RAM, ABI), что именно измерено и чего замер **не** доказывает. Развёрнутые агрегаты лежат в [results/](results/) — публичные JSON без приватных записей и расшифровок. Приватные артефакты (аудио, тексты, веса, логи) остаются в игнорируемых локальных каталогах.

## Выбор моделей и ресурсы

- [android-models-4gb.md](android-models-4gb.md) — исходная серия 6 ноября: 15 измеренных запусков (VAD, диаризация, GigaAM v2, Zipformer, Qwen3-0.6B) на AVD 4 ГБ; карта компонентов конвейера; короткие списки кандидатов ASR/диаризации/LLM и критерии решения. Исторический baseline.
- [pipeline-2gb.md](pipeline-2gb.md) — конвейер с бюджетом 2 ГБ: принятый профиль качества, полные сквозные прогоны (21–29 с аудио), стресс-пробы Nemotron на 30 минутах, отклонённые по памяти конфигурации Whisper.
- [model-selection.md](model-selection.md) — итоговые решения по каждой модели с обоснованием.
- [optimization.md](optimization.md) — всё, что ускорило конвейер и уложило его в память.

## Диаризация

- [synthetic-diarization-2026-11-08.md](synthetic-diarization-2026-11-08.md) — 5 ivkond + 5 BookMix через production FAST APK на Vexto_3: WER, DER экспорта/сырого Nemotron, RAM/время и ограничения синтетической разметки.

- [quick-audio-features.md](quick-audio-features.md) — 6 ноября: сравнение 7 конфигураций диаризации (Nemotron Q8/F32, Sortformer, pyannote+TitaNet/CAM++/ERes2NetV2) на фрагментах AMI; Emo и шумоподавление в сквозном прогоне.
- [russian-diarization.md](russian-diarization.md) — 7 ноября: русские разговоры, Community-1, полный NeMo MSDD; INT8 против FP32 segmentation; прямой DER production-диаризатора из dev APK.
- [nemotron-android.md](nemotron-android.md) — сборка Nemotron 3 на Android: закреплённые исходники, конвертация F32-весов, лимиты памяти по длительности.
- [diarization-compaction-avd.md](diarization-compaction-avd.md) — вырезание длинных пауз: замер до/после на 305-секундной записи.
- [nemotron-precision-ci-2026-11-07.md](nemotron-precision-ci-2026-11-07.md) и [nemotron-followup-ci-2026-11-07.md](nemotron-followup-ci-2026-11-07.md) — F32/F16/Q8 и число потоков; стандарт F32.
- [speaker-matching-quality.md](speaker-matching-quality.md) — междиалоговое узнавание людей: ERes2Net против ECAPA/WeSpeaker, калибровка порогов, исправление FAST-ветки (0/42 пропусков после фикса).

## ASR и качество расшифровки

- [audio-pipeline-refinement.md](audio-pipeline-refinement.md) — 7 ноября: сквозные прогоны APK (Poem, локальный разговор, on-demand чанки, scoped-вызовы Qwen), Pears04 с ручной разметкой: DER 11,15%, WER 26–39% по моделям.
- [quality-export-turns.md](quality-export-turns.md) — сырой GigaAM в экспорте против выбора Qwen; размер чанка 10→30 с и влияние на WER.
- [whisper-android.md](whisper-android.md) — рецепт сборки нативного whisper.cpp для Android.
- [fast-asr-models.md](fast-asr-models.md) — происхождение и закрепление весов быстрого профиля (CTC + Whisper Small Q5_1).
- [legacy-engine.md](legacy-engine.md) — исторический движок до quality-профиля: его замеры не относятся к новой связке.

## Языки, перебивания, корректор

- [spoken-language-id.md](spoken-language-id.md) — сравнение ECAPA и Whisper tiny; собственная дистилляция до 23,3 МБ с теми же ответами.
- [android-overlap.md](android-overlap.md) — детектор перебиваний в приложении: одна центральная модель WavLM, окно 4 с, порог, значок на аватаре, проверка переноса на Android.
- [natural-overlap-precision.md](natural-overlap-precision.md) — обучение детектора на реальной одновременной речи AMI: WavLM Base+ + голова (3 073 параметра), приоритет точности (94,7% верных срабатываний, 1 ложная тревога/56), протокол с фиксацией порога до test.
- [quick-audio-features.md](quick-audio-features.md) — пробы GTCRN и проба «исправь слово» через Qwen (0 применённых лексических правок), из которых вырос корректор RUPunct как отдельный слой.

## CI-пробы

- [nemotron-precision-ci-2026-11-07.md](nemotron-precision-ci-2026-11-07.md) / [nemotron-followup-ci-2026-11-07.md](nemotron-followup-ci-2026-11-07.md) — пробы точности весов и потоков Nemotron в CI.

## Результаты

| Файл | Что внутри |
|---|---|
| [results/android-4gb-2026-11-06.json](results/android-4gb-2026-11-06.json) | 15 измеренных запусков исходной серии |
| [results/artifacts-2026-11-06.json](results/artifacts-2026-11-06.json) | манифест артефактов исходной серии |
| [results/pipeline-2gb-measurements-2026-11-06.json](results/pipeline-2gb-measurements-2026-11-06.json) | агрегаты прогонов 2 ГБ |
| [results/pipeline-2gb-artifacts-2026-11-06.json](results/pipeline-2gb-artifacts-2026-11-06.json) | хеши моделей и runtime серии 2 ГБ |
| [results/quick-audio-2026-11-06.json](results/quick-audio-2026-11-06.json) | диаризация/Emo/шумоподавление, короткие пробы |
| [results/russian-diarization-2026-11-07.json](results/russian-diarization-2026-11-07.json) | русские разговоры и сравнения диаризации |
| [results/speaker-matching-2026-11-07.json](results/speaker-matching-2026-11-07.json) | междиалоговое узнавание, все метрики |
| [results/apk-pears04-2026-11-07.json](results/apk-pears04-2026-11-07.json) | DER/WER полного APK на размеченном фрагменте |
| [results/dev-diarization-2026-11-07.json](results/dev-diarization-2026-11-07.json) | прямой DER диаризатора dev APK |
| [results/nemotron-precision-ci-2026-11-07.json](results/nemotron-precision-ci-2026-11-07.json) / [nemotron-followup-ci-2026-11-07.json](results/nemotron-followup-ci-2026-11-07.json) | CI-пробы Nemotron |
| [results/android-overlap-2026-11-07.json](results/android-overlap-2026-11-07.json) | перенос детектора перебиваний на Android |
| [results/natural-overlap-precision-2026-11-07.json](results/natural-overlap-precision-2026-11-07.json) | обучение детектора на реальной речи, все агрегаты |
