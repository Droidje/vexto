# engine: офлайн-движок расшифровки

Android-библиотека на Kotlin (`com.vexto.engine`): аудиофайл → русский текст, спикеры и таймкоды. Работает без сети, все модели внутри APK.

## Подготовка

AAR sherpa-onnx (49 МБ) и модели (~270 МБ) в git не лежат. Перед первой сборкой:

```sh
scripts/fetch_models.sh                 # AAR + модели по умолчанию, sha256 проверяется
./gradlew :engine:testDebugUnitTest :app:assembleDebug
```

Скачанное кэшируется в `~/.cache/vexto-models`, повторный запуск копирует из кэша. Файлы ложатся в `engine/libs/` и `engine/src/main/assets/models/` (оба пути в `.gitignore`). Скрипт пишет `assets/models/manifest.json`: какие файлы ASR и эмбеддингов брать и порог кластеризации. Смена модели не требует правки кода:

```sh
scripts/fetch_models.sh --asr gigaam-v3-rnnt-punct   # своя пунктуация и регистр
scripts/fetch_models.sh --emb titanet-small --threshold 0.45
```

| параметр | варианты | по умолчанию |
|---|---|---|
| `--asr` | `gigaam-v3-rnnt`, `gigaam-v3-rnnt-punct`, `gigaam-v2-rnnt` | `gigaam-v3-rnnt` |
| `--emb` | `eres2net`, `titanet-small`, `campplus` | `eres2net` |
| `--threshold` | косинус для кластеризации спикеров, для eres2net 0,35–0,40 | `0.35` |

При первом запуске модели копируются из assets в `filesDir/models` (sherpa-onnx грузит по пути), перед копированием проверяется свободное место. Повторная копия делается только при смене `version` в манифесте.

## Конвейер

1. Декодирование: `MediaExtractor` + `MediaCodec` (wav, mp3, m4a/aac, ogg/opus, flac, amr), даунмикс в моно, полифазный sinc-ресемплер в 16 кГц, PCM16 во временный файл в `cacheDir`. В памяти только энергия кадров 10 мс.
2. Медленная АРУ (окно 0,5 с, усиление до +20 дБ) при чтении кусков: тихий собеседник иначе теряется в VAD.
3. Диаризация: silero VAD → окна 1 с с шагом 0,5 с → эмбеддинг ERes2Net на окно → агломеративная кластеризация (средняя связь, порог 0,35) → кластеры меньше 5 с речи вливаются в ближайший → голосование окон по кадрам 10 мс.
4. Распознавание: silero VAD (паузы от 0,3 с, поля 0,1 с), сегменты длиннее 20 с режутся в самом тихом месте, GigaAM v3 RNNT по одному сегменту, таймкоды токенов → слова.
5. Сборка: слово получает спикера с наибольшим перекрытием, соседние слова одного спикера с паузой до 1 с склеиваются в реплику, `speaker_N` нумеруются по первому появлению.
6. Пунктуация: для ASR без своей пунктуации только заглавная буква и точка в конце реплики, слова не меняются.

Модели грузятся по одной и освобождаются после своего этапа. Одновременно идёт одна расшифровка, остальные ждут.

Выбор моделей и параметров сделан по замерам на синтетических диалогах (TTS-голоса, сдвиг высоты, шум 10 дБ) и живой речи: WER 8–12 %, DER 10–15 %. Встроенная в sherpa-onnx диаризация pyannote + FastClustering на тех же данных давала DER вдвое выше.

## API для UI (Java)

```java
ReflectEngine.Task task = ReflectEngine.transcribe(context, audioUri, new TranscriptionOptions(), new ReflectEngine.Listener() {
    @Override public void onProgress(Stage stage, float fraction) {
        progressText.setText(stage.getTitle() + " " + Math.round(fraction * 100) + "%");
    }
    @Override public void onPartial(Transcript transcript) {
        adapter.show(transcript.getUtterances());   // распознанная часть, обновляется раз в 2 с
    }
    @Override public void onDone(Transcript transcript, ProcessingStats stats) {
        String json = TranscriptExport.json(transcript);   // формат проверки заказчика
    }
    @Override public void onError(Throwable error) { }
});
// task.cancel() останавливает расшифровку, колбэки больше не придут
```

- Колбэки приходят в главном потоке, вся работа в фоне.
- `new TranscriptionOptions(2)` задаёт известное число спикеров, по умолчанию оно определяется само.
- Этапы `Stage`: `Preparing` (копирование моделей, только при первом запуске), `Decoding`, `Diarization`, `Recognition`, `Assembly`, `Punctuation`. У каждого `getTitle()` для экрана.
- `Transcript.getUtterances()`: `Utterance` с `getSpeakerId()`, `getStartMs()`, `getEndMs()`, `getText()`, `getWords()`. `Transcript.speaker(id).displayName()` даёт «Спикер N» или имя.
- Экспорт: `TranscriptExport.json/text/markdown/srt(transcript)` или `render(transcript, ExportFormat.Srt)`; у `ExportFormat` есть расширение и MIME-тип. JSON всегда с `speaker_N`, имена спикеров туда не попадают.
- `ProcessingStats`: `getAudioMs()`, `getProcessingMs()`, `getRtf()`, `getStageMs()`, `getPeakMemoryMb()`.

Из Kotlin: `TranscriptionEngine(context).transcribe(uri, options)` возвращает `Flow<TranscriptionEvent>` (`Progress`, `Partial`, `Done`), отмена через отмену корутины.

## Прогон без UI

В debug-сборке есть `EvalActivity`. Она обрабатывает все аудиофайлы в `/sdcard/Android/data/com.vexto.reflect/files/eval/` и для каждого `<name>.wav` пишет рядом `<name>.json` (схема заказчика) и `<name>.stats.json` (`audio_ms`, `processing_ms`, `rtf`, `peak_rss_mb`, `threads`, `stage_ms`). В конце появляется файл `_done` (`ok N` или текст ошибки).

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
E=com.vexto.reflect/com.vexto.engine.eval.EvalActivity
D=/sdcard/Android/data/com.vexto.reflect/files/eval
adb shell am start -W -n $E                  # первый запуск: создаёт каталог и ставит модели
adb push data/*.wav $D/
adb shell am start -S -n $E                  # -e dir <путь> другой каталог, --ei speakers 2 число спикеров
until adb shell ls $D/_done >/dev/null 2>&1; do sleep 3; done
adb pull $D/. hyp/ && python3 tools/eval/score.py ref/ hyp/
adb logcat -s VextoEval                      # RTF, пик памяти и время этапов по каждому файлу
```

Каталог должно создать само приложение: если создать его через `adb shell mkdir`, приложение не сможет туда писать.

## Raw DER без распознавания текста

В debug-сборке есть `com.vexto.engine.eval.DiarizationEvalActivity`. Она вызывает
тот же production-декодер, VAD и `Diarizer` с `speakerCount=null`, но сохраняет
сырой `<stem>.rttm` до ASR, `<stem>.stats.json` и `_done` (`ok N` / `error`).
Stats содержат SHA256 WAV/RTTM/манифеста, времена этапов и VmHWM процесса.

Intent `--es dir <unique-slug>` выбирает app-owned подкаталог
`getExternalFilesDir("diar-eval")/<unique-slug>`; `--ei threads 4` задаёт 1–4 потока.
Пустой каталог делает bootstrap установки моделей. После `ok 0` поместите туда
тестовые WAV и удалите только `_done` своего bootstrap-запуска перед повторным стартом.
Готовые RTTM/stats не перезаписываются: следующий независимый прогон использует
новый slug. Для отдельных замеров запускайте каждый клип в свежем процессе.

После `adb pull` оценка на ноутбуке (reference модели на Android не передаётся):

```sh
python tools/benchmarks/score_dev_diarization.py --manifest MANIFEST.json --hypotheses-dir HYP_DIR --output SCORE.json
```

Scorer требует те же хеши русских фикстур, что у сохранённого Nemotron baseline,
`status=completed`, `forced_speaker_count=null`, `asr_executed=false` и связанный
хеш RTTM. При неполном/невалидном прогоне успешный pooled DER не публикуется.
[Результат прямого сравнения dev](../docs/research/russian-diarization.md#прямое-сравнение-с-диаризатором-из-dev-apk).

## Замеры на эмуляторе

AVD Android 35 arm64, 4 vCPU, 4 ГБ RAM, хост Apple M4 Max, 4 потока, 24 wav-файла 16 кГц общей длиной 16,6 мин:

| | значение |
|---|---|
| RTF всего | 0,080 (по файлам 0,055–0,108) |
| RTF по этапам | декодирование 0,005, диаризация 0,039, распознавание 0,037 |
| RTF, mp3/m4a/ogg/flac 44,1–48 кГц стерео | 0,11–0,12, из них декодирование и ресемплинг ≈ 0,03–0,045 |
| пиковый RSS процесса | 440–482 МБ |
| длинные записи 10,4 и 31 мин | пик RSS 453 и 483 МБ, память от длины почти не зависит; RTF 0,29 и 0,42 при сильной фоновой нагрузке хоста (load average > 40), чистого замера нет |
| WER / cpWER на 24 файлах | 10,2 % / 16,6 % (desktop-эталон того же конвейера 9,8 % / 15,6 %) |

x86_64 в APK есть, но на Apple Silicon не проверялся.
