# Локальная диаризация и фоновая идентификация спикеров между разговорами

Технический конспект: как получить расшифровку с метками спикеров сразу, а в фоне (асинхронно) сопоставлять голоса с постоянной базой людей — без блокировки основного пайплайна и с возможностью отката.

Источники стека:
- pyannote.audio — https://github.com/pyannote/pyannote-audio
- SpeechBrain ECAPA-TDNN — https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb

---

## 1. Ключевая идея

Здесь две разные задачи:

| Задача | Что отвечает | Где живёт |
|---|---|---|
| Диаризация | «кто говорил когда» внутри одной записи: `SPEAKER_00`, `SPEAKER_01` | основной (быстрый) путь |
| Идентификация | «`SPEAKER_00` этой сессии — это Анна (`G-17`) из базы» | фоновый worker |

Метки диаризатора **локальны для сессии**: `SPEAKER_00` в двух разных записях — не обязательно один и тот же человек.

**ECAPA сама ничего не объединяет.** Модель только превращает аудио в вектор (эмбеддинг). Сравнение, пороги, решение «тот же / новый / неизвестно», слияние и откат — это ваша прикладная логика.

## 2. Архитектура

```
 аудио ──► [ASR + диаризация] ──► сегменты + session-local IDs ──► UI (сразу)
                    │
                    └─► событие "session_ready" ──► очередь задач (SQLite)
                                                         │
                                          [background worker, 1..N]
                                            1. выбрать чистые фрагменты
                                            2. извлечь эмбеддинги (ECAPA)
                                            3. агрегировать по local speaker
                                            4. сравнить с global профилями
                                            5. записать новую версию привязки
                                                         │
                                          событие "mapping_updated" ──► UI перерисовывает имена
```

Принципы:
1. Основной путь никогда не ждёт идентификацию. Пользователь видит `Спикер 1`, позже — `Анна (предположительно)`.
2. Привязка `local → global` хранится отдельно от сегментов и версионируется. Текст расшифровки не переписывается — имена подставляются через join по актуальной версии привязки. Поэтому откат дёшев.
3. Все действия worker'а идемпотентны: повтор задачи даёт тот же результат.

ASR-модель и оборудование не важны: схема требует только сегментов `(start, end, local_speaker, text)`.

## 3. Схема хранения (SQLite)

```sql
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  audio_path TEXT NOT NULL,
  created_at REAL NOT NULL,
  consent_status TEXT NOT NULL DEFAULT 'unknown'   -- unknown|granted|denied
);

CREATE TABLE segments (
  id INTEGER PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES sessions(id),
  local_speaker TEXT NOT NULL,          -- SPEAKER_00
  start_s REAL NOT NULL, end_s REAL NOT NULL,
  text TEXT,
  overlap INTEGER NOT NULL DEFAULT 0    -- 1 если пересекается с другим спикером
);

CREATE TABLE speakers (                 -- постоянная база людей
  id TEXT PRIMARY KEY,                  -- G-17
  display_name TEXT,
  status TEXT NOT NULL DEFAULT 'active',-- active|merged|deleted
  merged_into TEXT,
  consent INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL
);

CREATE TABLE embeddings (
  id INTEGER PRIMARY KEY,
  session_id TEXT NOT NULL,
  local_speaker TEXT NOT NULL,
  model_id TEXT NOT NULL,               -- 'speechbrain/spkrec-ecapa-voxceleb@<rev>'
  kind TEXT NOT NULL,                   -- 'chunk' | 'session_centroid'
  vec BLOB NOT NULL,                    -- float32[192], L2-нормализован
  duration_s REAL, quality REAL,
  UNIQUE(session_id, local_speaker, model_id, kind, duration_s, quality)
);

CREATE TABLE speaker_profile_items (    -- какие сессионные центроиды «доверены» профилю
  speaker_id TEXT NOT NULL REFERENCES speakers(id),
  embedding_id INTEGER NOT NULL REFERENCES embeddings(id),
  added_by TEXT NOT NULL,               -- 'auto'|'user'
  added_at REAL NOT NULL,
  PRIMARY KEY (speaker_id, embedding_id)
);

CREATE TABLE speaker_mappings (         -- версии соответствий (append-only)
  id INTEGER PRIMARY KEY,
  session_id TEXT NOT NULL,
  local_speaker TEXT NOT NULL,
  global_speaker TEXT,                  -- NULL = unknown
  decision TEXT NOT NULL,               -- match|new|unknown|manual|revert
  score REAL, margin REAL,
  model_id TEXT NOT NULL, threshold_version TEXT NOT NULL,
  version INTEGER NOT NULL,
  is_current INTEGER NOT NULL DEFAULT 1,
  created_at REAL NOT NULL, created_by TEXT NOT NULL,
  UNIQUE(session_id, local_speaker, version)
);

CREATE TABLE jobs (
  id INTEGER PRIMARY KEY,
  kind TEXT NOT NULL,                   -- 'identify_session'
  dedup_key TEXT NOT NULL UNIQUE,       -- идемпотентность: kind:session:model
  payload TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued',-- queued|running|done|failed|dead
  attempts INTEGER NOT NULL DEFAULT 0,
  run_after REAL NOT NULL DEFAULT 0,
  locked_until REAL, last_error TEXT
);

CREATE TABLE events (                   -- outbox для UI
  id INTEGER PRIMARY KEY, kind TEXT, session_id TEXT, payload TEXT, created_at REAL
);
```

Почему так:
- `model_id` в эмбеддингах обязателен: векторы разных моделей (и даже разных версий) несравнимы. При смене модели — пересчёт, а не смешивание.
- `speaker_mappings` — только добавление; «текущая» версия помечена `is_current`. Откат = новая запись `revert`, указывающая на предыдущее состояние.
- Профиль спикера — не одно число, а набор доверенных сессионных центроидов. Его можно пересобрать, исключив ошибочную сессию.

## 4. Очередь задач

- **Постановка**: после диаризации — `INSERT OR IGNORE INTO jobs(... dedup_key='identify:<session>:<model>')`. Повторный вызов не создаст дубль.
- **Захват**: атомарный `UPDATE ... SET status='running', locked_until=now+lease WHERE id=(SELECT ... status='queued' AND run_after<=now LIMIT 1) RETURNING *` (SQLite ≥ 3.35). Lease защищает от зависших worker'ов: истёк — задачу берёт другой.
- **Повторы**: экспоненциальная задержка `run_after = now + min(2**attempts * 5, 600)`, после `max_attempts` → `dead` с сохранённой ошибкой. Ошибки «нет файла / нет согласия» — не повторять.
- **Идемпотентность**: эмбеддинги пишутся с `UNIQUE`/`INSERT OR IGNORE`; новая версия mapping создаётся только если решение изменилось.
- **SQLite**: `PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;`, короткие транзакции, тяжёлые вычисления — вне транзакции.

## 5. Фон ≠ отсутствие конкуренции за ресурсы

«Асинхронно» значит лишь, что UI и основной пайплайн не ждут результата. Но если worker работает на той же машине, он **делит CPU, GPU, VRAM, память и диск** с ASR/диаризацией и может их замедлить. Поэтому нужно явно управлять ресурсами:

- **Ограниченная конкурентность**: фиксированный пул (например, 1 GPU-worker), семафор на число одновременных инференсов; `torch.set_num_threads(k)` для CPU, чтобы не забрать все ядра.
- **Приоритет**: основной путь важнее. Worker может приостанавливаться, пока идёт активная расшифровка (флаг/семафор «foreground busy»), или работать на CPU, оставив GPU для ASR — выбирать по замерам.
- **Batching**: объединять чанки нескольких спикеров/сессий в батч `encode_batch` с паддингом и `wav_lens`; размер батча ограничен по памяти.
- **Backpressure**: если очередь > N или отставание > T — не принимать новые задачи на идентификацию (сессия остаётся с local IDs, задача ставится позже), уменьшать число чанков на спикера, откладывать перепроверки старых сессий.
- **Отдельный процесс** лучше потока: GIL, изоляция падений, независимый лимит памяти; можно задать `nice`/`ionice`.
- Модель загружается **один раз** на процесс, не на каждую задачу.

Скорость не обещается: её нужно измерять на вашем железе (см. §12).

## 6. Выбор чистых фрагментов

Плохие фрагменты дают «смешанные» эмбеддинги — главная причина ложных совпадений.

1. Отбросить сегменты, пересекающиеся с речью другого спикера (pyannote выдаёт перекрывающиеся интервалы — проверяйте пересечения; можно использовать `exclusive`-разметку, если она есть в вашей версии pipeline).
2. Обрезать края сегмента на 0.1–0.25 с (границы диаризации неточны).
3. Минимальная длина чанка ~1.5–2 с, длинные сегменты резать на окна 2–4 с с шагом.
4. Фильтры качества: энергия/VAD-доля речи, клиппинг, слишком тихо.
5. Лимит: до K лучших чанков на спикера (например, 10–30), предпочитая длинные.
6. Если суммарно чистой речи мало (например < 6–10 с) — решение `unknown`, не угадывать.

Все цифры — стартовые, подбираются на ваших данных.

## 7. Аудио, эмбеддинги, нормализация, агрегация

- Вход модели — **16 kHz, mono** (так указано в карточке модели). `encode_batch` не ресемплирует сам: приводите аудио заранее (`torchaudio.functional.resample`, усреднение каналов).
- Выход — 192-мерный вектор на чанк (форма `[batch, 1, 192]`).
- Нормализация: L2 каждого чанкового вектора → среднее → снова L2. Это сессионный центроид спикера.
- Робастность: отбросить чанки, чья косинусная близость к предварительному центроиду заметно ниже медианы (выбросы — чужая речь/шум), и пересчитать.
- Профиль глобального спикера: центроид доверенных сессионных центроидов (или хранить все и брать max/top-k средний — устойчивее к разным микрофонам).
- Опционально: score normalization (S-norm по когорте чужих векторов) — снижает зависимость порога от условий записи.

Cosine similarity для L2-нормализованных векторов — просто скалярное произведение: `s = a · b`, диапазон [-1, 1].

## 8. Решение: match / new / unknown

Для каждого local speaker сессии:

```
s1, g1 = лучший глобальный кандидат
s2     = второй кандидат (другой человек)
margin = s1 - s2

если чистой речи мало                    → unknown
если s1 ≥ T_accept и margin ≥ M          → match(g1)
если s1 <  T_new   (далеко от всех)      → new (кандидат), либо unknown до подтверждения
иначе (серая зона)                       → unknown, s1/g1 сохраняются как подсказка
```

Дополнительные ограничения:
- **Одна сессия — один глобальный ID на одного local speaker, и разные local speakers одной сессии не могут получить один и тот же global ID** (люди в одном разговоре — разные). Решайте назначение совместно (жадно по убыванию score или венгерским алгоритмом), а не независимо.
- **Отрыв от второго кандидата** защищает от похожих голосов (родственники, один микрофон).
- **Новый спикер**: не создавайте постоянный профиль после одной короткой сессии. Держите «provisional» профиль; повышайте до active после подтверждения пользователем или после N совпадений в разных сессиях.

### Калибровка порогов

Пороги из чужих статей не переносятся: зависят от микрофонов, языка, шумов, длины речи.
1. Соберите размеченный набор: пары «тот же / разные» из ваших реальных записей (разные дни, устройства).
2. Посчитайте распределения score, построите DET/ROC, EER.
3. Выберите `T_accept` по целевой доле ложных совпадений (FAR, например ≤ 0.5%) — ложное совпадение хуже, чем «unknown».
4. Подберите `M` и минимальную длительность так же.
5. Сохраните как `threshold_version`; при смене — пересчёт решений новой версией (это просто новые версии mapping).

## 9. Накопление доказательств и запрет опасных слияний

- Автоматически пополнять профиль только при **высокой уверенности** (строже, чем для match) — иначе профиль «дрейфует» к чужому голосу и ошибки самоусиливаются.
- **Слияние двух глобальных спикеров** (`G-17` и `G-42` — один человек) **автоматически не выполнять**. Система может предлагать: «центроиды близки, в N сессиях совпадения, никогда не звучали одновременно». Применяет — человек.
- Жёсткие запреты автоматического слияния:
  - два ID встречались **в одной сессии** как разные local speakers;
  - у одного из профилей ручное имя/подтверждение пользователя;
  - мало доказательств (меньше N сессий или мало секунд речи);
  - разные `model_id`.
- Слияние реализуется как `speakers.status='merged', merged_into=...` — без удаления данных; при чтении ID разрешается по цепочке.

## 10. Откат

- Откат привязки сессии: новая запись `speaker_mappings` с `decision='revert'`, копирующая предыдущую версию; `is_current` переключается в одной транзакции.
- Откат слияния: `status='active', merged_into=NULL`; профильные элементы не терялись.
- Исключение ошибочной сессии из профиля: удалить строки из `speaker_profile_items` и пересчитать центроид.
- Ручные решения (`created_by='user'`) имеют приоритет — worker их не перезаписывает.
- Аудит-лог (кто, когда, score, версия порога) — уже есть в `speaker_mappings`.

## 11. Событийное обновление UI

- Worker после фиксации решения пишет строку в `events` в той же транзакции (transactional outbox).
- Сервер отдаёт события через SSE/WebSocket или UI опрашивает `events WHERE id > last_seen`.
- UI показывает статусы: `Спикер 1` → `Анна?` (match, ещё не подтверждено) → `Анна` (подтверждено) и кнопки «Это не Анна», «Это новый человек», «Объединить».
- Действие пользователя = новая версия mapping с `created_by='user'` + событие.

## 12. Повторное использование эмбеддингов диаризатора

pyannote внутри тоже считает speaker embeddings. Их можно использовать вместо отдельного прохода ECAPA **только если**:
- это та же модель эмбеддингов (с той же версией весов), что и у сохранённых профилей — то есть одно векторное пространство;
- известна предобработка и окна, на которых они посчитаны;
- они доступны через стабильный API вашей версии pyannote, а не внутренние детали.

Векторы pyannote-модели и SpeechBrain ECAPA **несравнимы** между собой, даже если размерности совпали бы. Если пространство разное — либо отдельный ECAPA-проход (проще и предсказуемее), либо целиком переходить на одну модель и пересчитать базу. Экономию ресурсов проверяйте замером.

## 13. Минимальный каркас на Python

Один файл, без веб-сервера. Диаризация через pyannote (нужен токен HF и принятие условий модели), ASR — заглушка: подключите свою модель.

```python
# speaker_id_skeleton.py — каркас, не продакшн-код
import json, sqlite3, time, uuid
import numpy as np
import torch, torchaudio

DB = "speakers.db"
MODEL_ID = "speechbrain/spkrec-ecapa-voxceleb"
SR = 16000
T_ACCEPT, MARGIN, MIN_SPEECH_S = 0.60, 0.08, 8.0   # ПРИМЕР, калибровать!
THRESH_VER = "v0-uncalibrated"

def db():
    c = sqlite3.connect(DB, timeout=10)
    c.execute("PRAGMA journal_mode=WAL"); c.execute("PRAGMA busy_timeout=5000")
    return c

def init_db():
    with db() as c:
        c.executescript(open("schema.sql").read())   # схема из §3

# ---------- producer: ASR + диаризация ----------
def load_audio(path):
    wav, fs = torchaudio.load(path)
    wav = wav.mean(0, keepdim=True)                  # mono
    if fs != SR:
        wav = torchaudio.functional.resample(wav, fs, SR)
    return wav

def run_asr(wav, start, end):
    return ""                                        # подключите свою ASR

def produce(audio_path, hf_token):
    from pyannote.audio import Pipeline
    pipe = Pipeline.from_pretrained("pyannote/speaker-diarization-3.1",
                                    use_auth_token=hf_token)  # имя аргумента зависит от версии pyannote
    wav = load_audio(audio_path)
    diar = pipe({"waveform": wav, "sample_rate": SR})
    turns = [(t.start, t.end, spk) for t, _, spk in diar.itertracks(yield_label=True)]
    sid = str(uuid.uuid4())
    with db() as c:
        c.execute("INSERT INTO sessions(id,audio_path,created_at,consent_status) VALUES(?,?,?,?)",
                  (sid, audio_path, time.time(), "granted"))
        for i, (s, e, spk) in enumerate(turns):
            ov = any(o[2] != spk and o[0] < e and o[1] > s for o in turns)
            c.execute("INSERT INTO segments(session_id,local_speaker,start_s,end_s,text,overlap)"
                      " VALUES(?,?,?,?,?,?)", (sid, spk, s, e, run_asr(wav, s, e), int(ov)))
        c.execute("INSERT OR IGNORE INTO jobs(kind,dedup_key,payload) VALUES(?,?,?)",
                  ("identify_session", f"identify:{sid}:{MODEL_ID}", json.dumps({"session": sid})))
        c.execute("INSERT INTO events(kind,session_id,payload,created_at) VALUES(?,?,?,?)",
                  ("session_ready", sid, "{}", time.time()))
    return sid                                       # UI уже может показывать local IDs

# ---------- worker ----------
class Embedder:
    def __init__(self, device="cpu"):
        from speechbrain.inference.speaker import EncoderClassifier
        self.m = EncoderClassifier.from_hparams(source=MODEL_ID,
                    savedir="pretrained_models/spkrec-ecapa-voxceleb",
                    run_opts={"device": device})
    @torch.no_grad()
    def embed(self, chunks):                         # list[Tensor[T]] @16k mono
        L = max(x.shape[-1] for x in chunks)
        batch = torch.stack([torch.nn.functional.pad(x, (0, L - x.shape[-1])) for x in chunks])
        lens = torch.tensor([x.shape[-1] / L for x in chunks])
        e = self.m.encode_batch(batch, wav_lens=lens).squeeze(1).cpu().numpy()
        return e / np.linalg.norm(e, axis=1, keepdims=True)

def clean_chunks(wav, segs, trim=0.2, win=3.0, min_len=1.5, k=20):
    out = []
    for s, e in segs:
        s, e = s + trim, e - trim
        while e - s >= min_len:
            w = min(win, e - s)
            out.append(wav[0, int(s*SR):int((s+w)*SR)]); s += w
    out.sort(key=lambda x: -x.shape[-1])
    return out[:k]

def centroid(E):
    c = E.mean(0); c /= np.linalg.norm(c)
    keep = E[E @ c >= np.median(E @ c) - 0.1]        # отсев выбросов
    c = keep.mean(0); return c / np.linalg.norm(c)

def profiles(c):
    rows = c.execute("""SELECT p.speaker_id, e.vec FROM speaker_profile_items p
        JOIN embeddings e ON e.id=p.embedding_id JOIN speakers s ON s.id=p.speaker_id
        WHERE s.status='active' AND e.model_id=?""", (MODEL_ID,)).fetchall()
    by = {}
    for gid, b in rows: by.setdefault(gid, []).append(np.frombuffer(b, np.float32))
    return {g: centroid(np.stack(v)) for g, v in by.items()}

def claim(c):
    now = time.time()
    return c.execute("""UPDATE jobs SET status='running', attempts=attempts+1, locked_until=?
        WHERE id=(SELECT id FROM jobs WHERE (status='queued' AND run_after<=?)
                  OR (status='running' AND locked_until<?) ORDER BY id LIMIT 1)
        RETURNING id, payload, attempts""", (now + 300, now, now)).fetchone()

def identify(c, emb, sid):
    if c.execute("SELECT consent_status FROM sessions WHERE id=?", (sid,)).fetchone()[0] != "granted":
        return                                       # без согласия — без биометрии
    path = c.execute("SELECT audio_path FROM sessions WHERE id=?", (sid,)).fetchone()[0]
    wav = load_audio(path)
    spk_segs = {}
    for spk, s, e in c.execute("SELECT local_speaker,start_s,end_s FROM segments "
                               "WHERE session_id=? AND overlap=0", (sid,)):
        spk_segs.setdefault(spk, []).append((s, e))
    profs = profiles(c)
    cands = []
    for spk, segs in spk_segs.items():
        ch = clean_chunks(wav, segs)
        speech = sum(x.shape[-1] for x in ch) / SR
        if speech < MIN_SPEECH_S or not ch:
            cands.append((spk, None, None, None, None)); continue
        cen = centroid(emb.embed(ch)).astype(np.float32)
        c.execute("INSERT OR IGNORE INTO embeddings(session_id,local_speaker,model_id,kind,vec,duration_s)"
                  " VALUES(?,?,?,?,?,?)", (sid, spk, MODEL_ID, "session_centroid", cen.tobytes(), speech))
        eid = c.execute("SELECT id FROM embeddings WHERE session_id=? AND local_speaker=? AND model_id=? "
                        "AND kind='session_centroid'", (sid, spk, MODEL_ID)).fetchone()[0]
        sc = sorted(((float(cen @ v), g) for g, v in profs.items()), reverse=True)
        s1, g1 = sc[0] if sc else (-1.0, None)
        s2 = sc[1][0] if len(sc) > 1 else -1.0
        cands.append((spk, g1, s1, s1 - s2, eid))
    used = set()                                     # один global ID на сессию
    for spk, g1, s1, m, eid in sorted(cands, key=lambda x: -(x[2] or -9)):
        if g1 and s1 >= T_ACCEPT and m >= MARGIN and g1 not in used:
            decision, gid = "match", g1; used.add(g1)
        else:
            decision, gid = "unknown", None          # new — только после подтверждения
        save_mapping(c, sid, spk, gid, decision, s1, m)

def save_mapping(c, sid, spk, gid, decision, score, margin, by="worker"):
    cur = c.execute("SELECT global_speaker,decision,version,created_by FROM speaker_mappings "
                    "WHERE session_id=? AND local_speaker=? AND is_current=1", (sid, spk)).fetchone()
    if cur and cur[3] == "user" and by == "worker": return       # не перетирать человека
    if cur and (cur[0], cur[1]) == (gid, decision): return        # идемпотентность
    ver = (cur[2] + 1) if cur else 1
    c.execute("UPDATE speaker_mappings SET is_current=0 WHERE session_id=? AND local_speaker=?", (sid, spk))
    c.execute("""INSERT INTO speaker_mappings(session_id,local_speaker,global_speaker,decision,score,margin,
        model_id,threshold_version,version,is_current,created_at,created_by)
        VALUES(?,?,?,?,?,?,?,?,?,1,?,?)""",
        (sid, spk, gid, decision, score, margin, MODEL_ID, THRESH_VER, ver, time.time(), by))
    c.execute("INSERT INTO events(kind,session_id,payload,created_at) VALUES(?,?,?,?)",
              ("mapping_updated", sid, json.dumps({"local": spk, "global": gid, "decision": decision}), time.time()))

def revert(sid, spk):
    with db() as c:
        prev = c.execute("SELECT global_speaker FROM speaker_mappings WHERE session_id=? AND local_speaker=? "
                         "AND is_current=0 ORDER BY version DESC LIMIT 1", (sid, spk)).fetchone()
        save_mapping(c, sid, spk, prev[0] if prev else None, "revert", None, None, by="user")

def worker_loop(device="cpu", max_attempts=5):
    torch.set_num_threads(2)                         # ограничение CPU
    emb = Embedder(device)                           # загрузка один раз
    while True:
        with db() as c: job = claim(c)
        if not job: time.sleep(1.0); continue
        jid, payload, attempts = job
        try:
            with db() as c:                          # для продакшна: инференс вне транзакции
                identify(c, emb, json.loads(payload)["session"])
                c.execute("UPDATE jobs SET status='done' WHERE id=?", (jid,))
        except Exception as e:
            st = "dead" if attempts >= max_attempts else "queued"
            with db() as c:
                c.execute("UPDATE jobs SET status=?, last_error=?, run_after=? WHERE id=?",
                          (st, repr(e), time.time() + min(2 ** attempts * 5, 600), jid))
```

Что намеренно упрощено: инференс внутри транзакции (в продакшне — извлечь данные, посчитать, затем короткая транзакция записи), нет батчинга между сессиями, нет backpressure, «new speaker» создаётся только вручную. Подтверждение пользователем: `INSERT INTO speakers`, `INSERT INTO speaker_profile_items` с центроидом сессии и `save_mapping(..., by="user")`.

API SpeechBrain сверен с карточкой модели: `from speechbrain.inference.speaker import EncoderClassifier`, `from_hparams(source=..., savedir=..., run_opts={"device": "cuda"})`, `encode_batch(...)`. Старый импорт `speechbrain.pretrained` устарел.

## 14. Сведения о модели

- `speechbrain/spkrec-ecapa-voxceleb` — ECAPA-TDNN, обучена на VoxCeleb.
- Эмбеддинг: 192 измерения.
- Вход: 16 kHz, один канал.
- Устройства: CPU и CUDA (`run_opts={"device": "cuda"}`).
- Файл весов `embedding_model.ckpt` ≈ 83.3 MB. Это размер файла, а **не** потребление памяти процесса: добавляются PyTorch, CUDA-контекст, активации, батчи. Меряйте RSS/VRAM на своих данных.
- Скорость не указываю: зависит от железа, длины чанков, батча и нагрузки основного пайплайна — нужны замеры.

## 15. Тестирование

### Точность
- Набор: реальные записи вашего домена, несколько сессий на человека, разные дни/микрофоны/комнаты, похожие голоса.
- Метрики диаризации: DER (pyannote.metrics).
- Метрики идентификации: EER, FAR/FRR при выбранных порогах, доля `unknown`, ошибки назначения на уровне сессии, «ошибочные слияния» (должны быть ≈ 0).
- Тесты дрейфа: прогон 50+ сессий подряд с автопополнением — не сползают ли профили.
- Регрессия: фиксированный набор, запуск при смене модели/порогов/кода.

### Ресурсы
- Время на минуту аудио: отдельно ASR, диаризация, worker; при одиночной и параллельной работе.
- Влияние worker'а на основной путь: задержка ASR с ним и без него.
- Пиковая RAM/VRAM (`torch.cuda.max_memory_allocated`, RSS), загрузка CPU.
- Длина очереди и время от `session_ready` до `mapping_updated` под нагрузкой.
- Отказоустойчивость: убить worker посреди задачи — задача должна завершиться после истечения lease без дублей.

## 16. Безопасность и согласие

Голосовые эмбеддинги — **биометрические персональные данные** (в РФ — 152-ФЗ, ст. 11; в ЕС — GDPR, ст. 9). Практически:

- **Явное согласие** каждого участника на создание голосового профиля, отдельно от согласия на запись. Без согласия — только session-local IDs, эмбеддинги не сохраняются.
- Для детей/учеников — согласие законных представителей.
- Возможность отозвать согласие: удаление профиля, всех эмбеддингов и привязок (реальное удаление, не `status='deleted'`).
- Шифрование БД и аудио на диске (SQLCipher/шифрование тома), ограничение доступа к файлу, без отправки во внешние сервисы.
- Минимизация: хранить центроиды вместо чанковых векторов, когда это возможно; срок хранения сырого аудио.
- Журнал доступа и изменений; бэкапы тоже шифруются.
- Эмбеддинг не равен «анонимизации»: по нему можно опознать человека.
- Не использовать систему для идентификации людей без их ведома.

## 17. Чеклист

- [ ] Диаризация выдаёт local IDs, UI работает без worker'а
- [ ] Задача ставится идемпотентно, lease + retry + dead-letter
- [ ] Чистые фрагменты без перекрытий, минимум речи
- [ ] 16 kHz mono, L2-нормализация, робастный центроид
- [ ] Пороги и margin откалиброваны на своих данных, версия порогов сохранена
- [ ] Ограничение уникальности global ID внутри сессии
- [ ] Новые спикеры и слияния — только с подтверждением
- [ ] Версионированные привязки, откат, приоритет ручных решений
- [ ] Ограничение ресурсов worker'а и backpressure, замеры
- [ ] События для UI
- [ ] Согласие, шифрование, удаление по запросу
