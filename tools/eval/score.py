#!/usr/bin/env python3
"""Сравнение расшифровок Reflect с эталоном.

Оба файла в формате проверки заказчика:
{"transcript": [{"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 900, "text": "..."}]}

Метрики:
  WER     пословная ошибка по всему тексту после нормализации;
  cpWER   WER с учётом говорящих: текст каждого спикера сравнивается с текстом
          сопоставленного эталонного спикера, лучшее сопоставление по перестановкам;
  DER     доля ошибочно размеченного времени (пропуск, ложная речь, путаница спикеров),
          без допуска на границах, по кадрам 10 мс;
  bound   средняя ошибка границ реплик в мс для пар реплик, сопоставленных по перекрытию;
  RTF     время обработки к длительности, если рядом лежит <имя>.stats.json с processing_ms и audio_ms.

Запуск: python3 tools/eval/score.py REF_DIR HYP_DIR
Файлы сопоставляются по имени: REF_DIR/a.json ↔ HYP_DIR/a.json.
"""

from __future__ import annotations

import argparse
import itertools
import json
import re
import sys
from pathlib import Path

FRAME_MS = 10


def load(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return validate(data, path)


def validate(data, path: Path) -> list[dict]:
    if not isinstance(data, dict) or not isinstance(data.get("transcript"), list):
        raise ValueError(f"{path}: нет массива transcript")
    for i, item in enumerate(data["transcript"]):
        for key, kind in (("speaker_id", str), ("start_ms", int), ("end_ms", int), ("text", str)):
            if not isinstance(item.get(key), kind) or isinstance(item.get(key), bool):
                raise ValueError(f"{path}: реплика {i}: поле {key} должно быть {kind.__name__}")
        if item["end_ms"] < item["start_ms"]:
            raise ValueError(f"{path}: реплика {i}: end_ms меньше start_ms")
    return data["transcript"]


def normalize(text: str) -> list[str]:
    text = text.lower().replace("ё", "е")
    text = re.sub(r"[^\w\s]|_", " ", text)
    return text.split()


def edit_distance(ref: list[str], hyp: list[str]) -> int:
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1]


def words_by_speaker(items: list[dict]) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for item in sorted(items, key=lambda x: x["start_ms"]):
        out.setdefault(item["speaker_id"], []).extend(normalize(item["text"]))
    return out


def best_mapping(ref_ids: list[str], hyp_ids: list[str], score) -> dict[str, str]:
    """Сопоставление эталонных и найденных спикеров с минимальной ошибкой. Перебор, спикеров мало."""
    hyp_padded = hyp_ids + [None] * max(0, len(ref_ids) - len(hyp_ids))
    best, best_cost = {}, None
    for perm in itertools.permutations(hyp_padded, len(ref_ids)):
        mapping = dict(zip(ref_ids, perm))
        cost = score(mapping)
        if best_cost is None or cost < best_cost:
            best, best_cost = mapping, cost
    return best


def wer(ref: list[dict], hyp: list[dict]) -> tuple[int, int]:
    ref_words = [w for item in sorted(ref, key=lambda x: x["start_ms"]) for w in normalize(item["text"])]
    hyp_words = [w for item in sorted(hyp, key=lambda x: x["start_ms"]) for w in normalize(item["text"])]
    return edit_distance(ref_words, hyp_words), len(ref_words)


def cp_wer(ref: list[dict], hyp: list[dict]) -> tuple[int, int]:
    ref_spk, hyp_spk = words_by_speaker(ref), words_by_speaker(hyp)
    total = sum(len(w) for w in ref_spk.values())

    def cost(mapping):
        errors = sum(edit_distance(words, hyp_spk.get(mapping[r], [])) for r, words in ref_spk.items())
        unused = set(hyp_spk) - set(mapping.values())
        return errors + sum(len(hyp_spk[h]) for h in unused)

    mapping = best_mapping(list(ref_spk), list(hyp_spk), cost)
    return cost(mapping), total


def frames(items: list[dict], length: int) -> list[set[str]]:
    grid: list[set[str]] = [set() for _ in range(length)]
    for item in items:
        for f in range(item["start_ms"] // FRAME_MS, min(length, item["end_ms"] // FRAME_MS)):
            grid[f].add(item["speaker_id"])
    return grid


def der(ref: list[dict], hyp: list[dict]) -> float:
    length = max([x["end_ms"] for x in ref + hyp] + [0]) // FRAME_MS + 1
    ref_grid, hyp_grid = frames(ref, length), frames(hyp, length)
    ref_ids = sorted({x["speaker_id"] for x in ref})
    hyp_ids = sorted({x["speaker_id"] for x in hyp})

    overlap = {(r, h): 0 for r in ref_ids for h in hyp_ids}
    for rs, hs in zip(ref_grid, hyp_grid):
        for r in rs:
            for h in hs:
                overlap[(r, h)] += 1
    mapping = best_mapping(ref_ids, hyp_ids, lambda m: -sum(overlap.get((r, h), 0) for r, h in m.items() if h))

    speech = errors = 0
    for rs, hs in zip(ref_grid, hyp_grid):
        mapped = {h for r, h in mapping.items() if r in rs and h in hs}
        speech += len(rs)
        errors += max(len(rs), len(hs)) - len(mapped)
    return errors / speech if speech else 0.0


def boundary_error(ref: list[dict], hyp: list[dict]) -> float | None:
    diffs = []
    for r in ref:
        best = max(hyp, key=lambda h: min(r["end_ms"], h["end_ms"]) - max(r["start_ms"], h["start_ms"]), default=None)
        if best and min(r["end_ms"], best["end_ms"]) > max(r["start_ms"], best["start_ms"]):
            diffs += [abs(r["start_ms"] - best["start_ms"]), abs(r["end_ms"] - best["end_ms"])]
    return sum(diffs) / len(diffs) if diffs else None


def rtf(hyp_path: Path) -> float | None:
    stats = hyp_path.with_suffix(".stats.json")
    if not stats.exists():
        return None
    data = json.loads(stats.read_text(encoding="utf-8"))
    return data["processing_ms"] / data["audio_ms"] if data.get("audio_ms") else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ref_dir", type=Path)
    parser.add_argument("hyp_dir", type=Path)
    parser.add_argument("--json", type=Path, help="сохранить метрики в файл")
    args = parser.parse_args()

    rows, totals = [], {"wer": [0, 0], "cpwer": [0, 0]}
    for ref_path in sorted(args.ref_dir.glob("*.json")):
        if ref_path.name.endswith(".stats.json"):
            continue
        hyp_path = args.hyp_dir / ref_path.name
        if not hyp_path.exists():
            rows.append({"file": ref_path.stem, "error": "нет файла расшифровки"})
            continue
        try:
            ref, hyp = load(ref_path), load(hyp_path)
        except ValueError as error:
            rows.append({"file": ref_path.stem, "error": str(error)})
            continue
        w, n = wer(ref, hyp)
        cw, cn = cp_wer(ref, hyp)
        totals["wer"][0] += w
        totals["wer"][1] += n
        totals["cpwer"][0] += cw
        totals["cpwer"][1] += cn
        rows.append({
            "file": ref_path.stem,
            "wer": w / n if n else 0.0,
            "cpwer": cw / cn if cn else 0.0,
            "der": der(ref, hyp),
            "bound_ms": boundary_error(ref, hyp),
            "speakers_ref": len({x["speaker_id"] for x in ref}),
            "speakers_hyp": len({x["speaker_id"] for x in hyp}),
            "rtf": rtf(hyp_path),
        })

    def fmt(value, pattern):
        return "н/д" if value is None else pattern.format(value)

    print(f"{'файл':<28} {'WER':>7} {'cpWER':>7} {'DER':>7} {'границы':>9} {'спикеры':>9} {'RTF':>6}")
    for row in rows:
        if "error" in row:
            print(f"{row['file']:<28} ОШИБКА: {row['error']}")
            continue
        print(
            f"{row['file']:<28} {row['wer']:>7.1%} {row['cpwer']:>7.1%} {row['der']:>7.1%} "
            f"{fmt(row['bound_ms'], '{:.0f} мс'):>9} {row['speakers_hyp']:>4}/{row['speakers_ref']:<4} "
            f"{fmt(row['rtf'], '{:.3f}'):>6}"
        )
    w, n = totals["wer"]
    cw, cn = totals["cpwer"]
    if n:
        print(f"{'итого':<28} {w / n:>7.1%} {cw / cn:>7.1%}")
    if args.json:
        args.json.write_text(json.dumps({"files": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    return 1 if any("error" in row for row in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
