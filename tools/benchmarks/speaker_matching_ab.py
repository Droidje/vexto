"""Compare fixed/multi-sample speaker galleries; calibrate only on disjoint people.

Consumes real embedding JSON, does not run a recognizer or change Android profiles.
Known-person queries and leave-one-person-out unknown queries are reported separately.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path


def normalize(vector):
    if not vector or any(type(x) not in (int, float) or not math.isfinite(x) for x in vector):
        raise ValueError("Expected finite nonempty embedding")
    scale = max(abs(x) for x in vector)
    if not scale:
        raise ValueError("Zero embedding")
    scaled = [x / scale for x in vector]
    norm = math.sqrt(math.fsum(x * x for x in scaled))
    return [x / norm for x in scaled]


def cosine(a, b):
    if len(a) != len(b):
        raise ValueError("Embedding dimensions differ")
    return max(-1., min(1., math.fsum(x * y for x, y in zip(a, b))))


def score(query, samples, method):
    if method == "first":
        return cosine(query, samples[0])
    if method == "max":
        return max(cosine(query, sample) for sample in samples)
    if method == "mean":
        center = [math.fsum(values) for values in zip(*samples)]
        if max(abs(x) for x in center) < 1e-12:
            return -1.
        return cosine(query, normalize(center))
    raise ValueError("Unknown scoring method")


def validate(items):
    people, sessions = defaultdict(set), defaultdict(set)
    seen, dimensions = set(), set()
    for row in items:
        for key in ("person_id", "session_id", "local_id", "split"):
            if not isinstance(row.get(key), str) or not row[key]:
                raise ValueError(f"Missing {key}")
        if row["split"] not in ("calibration", "test"):
            raise ValueError("Invalid split")
        key = row["session_id"], row["local_id"]
        if key in seen:
            raise ValueError("Duplicate speaker/session")
        seen.add(key)
        people[row["split"]].add(row["person_id"])
        sessions[row["split"]].add(row["session_id"])
        dimensions.add(len(normalize(row["embedding"])))
    if len(dimensions) != 1 or not people["calibration"] or not people["test"]:
        raise ValueError("Need consistent dimensions and both splits")
    if people["calibration"] & people["test"] or sessions["calibration"] & sessions["test"]:
        raise ValueError("Calibration/test identity or session leakage")


def trials(items, split, enrollment_count, method):
    grouped = defaultdict(list)
    for row in items:
        if row["split"] == split:
            grouped[row["person_id"]].append(row)
    gallery, queries = {}, []
    for person, rows in sorted(grouped.items()):
        rows = sorted(rows, key=lambda r: (r["session_id"], r["local_id"]))
        if len({r["session_id"] for r in rows}) != len(rows) or len(rows) <= enrollment_count:
            raise ValueError("Each identity needs distinct enrollment and query meetings")
        gallery[person] = [normalize(r["embedding"]) for r in rows[:enrollment_count]]
        queries.extend(rows[enrollment_count:])
    if len(gallery) < 2:
        raise ValueError("Need competing identities")
    result = []
    for query in queries:
        q = normalize(query["embedding"])
        scores = sorted(((score(q, samples, method), person) for person, samples in gallery.items()),
                        key=lambda pair: (-pair[0], pair[1]))
        result.append((query["person_id"], scores))
    return result


def decision(scores, threshold, margin):
    if not scores or scores[0][0] < threshold:
        return None
    if len(scores) > 1 and (scores[0][0] == scores[1][0] or scores[0][0] - scores[1][0] < margin):
        return None
    return scores[0][1]


def metrics(rows, threshold, margin):
    correct = wrong = missed = unknown_false = pair_false = pair_negatives = 0
    for truth, scores in rows:
        predicted = decision(scores, threshold, margin)
        correct += predicted == truth
        wrong += predicted is not None and predicted != truth
        missed += predicted is None
        unknown = [(value, person) for value, person in scores if person != truth]
        unknown_false += decision(unknown, threshold, margin) is not None
        pair_false += sum(value >= threshold for value, _ in unknown)
        pair_negatives += len(unknown)
    return dict(known_queries=len(rows), correct=correct, missed=missed, wrong_identity=wrong,
                unknown_queries=len(rows), false_accept_unknown=unknown_false,
                pair_false_accept=pair_false, negative_pairs=pair_negatives)


def calibrate(rows):
    # Exhaustive thresholds from calibration scores only; zero observed false assignments.
    thresholds = sorted({1.000001} | {s for _, scores in rows for s, _ in scores}
                        | {math.nextafter(s, math.inf) for _, scores in rows for s, _ in scores})
    best = None
    for threshold in thresholds:
        for margin in (0., .04, .08, .12, .16):
            m = metrics(rows, threshold, margin)
            if m["wrong_identity"] or m["false_accept_unknown"]:
                continue
            rank = m["correct"], threshold, margin
            if best is None or rank > best[0]:
                best = rank, threshold, margin
    return best[1:]


def evaluate(path):
    raw = path.read_bytes()
    data = json.loads(raw)
    items = data["items"]
    validate(items)
    comparisons = []
    for enrollment_count in (2, 3):
        for method in ("first", "mean", "max"):
            training = trials(items, "calibration", enrollment_count, method)
            testing = trials(items, "test", enrollment_count, method)
            calibrated = calibrate(training)
            for policy, (threshold, margin) in (("fixed_0.80_0.08", (.80, .08)), ("calibration_only", calibrated)):
                comparisons.append(dict(enrollment_sessions=enrollment_count, method=method, policy=policy,
                                        threshold=threshold, margin=margin,
                                        calibration=metrics(training, threshold, margin),
                                        test=metrics(testing, threshold, margin)))
    return dict(input_sha256=hashlib.sha256(raw).hexdigest(), model=data.get("model"),
                evaluator_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                extractor_sha256=data.get("extractor_sha256"),
                model_revision=data.get("model_revision"), style=data.get("style"),
                manifest_sha256=data.get("manifest_sha256"), extraction_seconds=data.get("extraction_seconds"),
                profiles=len(items), people=len({r["person_id"] for r in items}),
                sessions=len({r["session_id"] for r in items}), comparisons=comparisons)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() in {p.resolve() for p in args.inputs}:
        parser.error("Output must not overwrite input embeddings")
    reports = [evaluate(path) for path in args.inputs]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"reports": reports}, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    for report in reports:
        print(report["model"], report["style"], "profiles:", report["profiles"])
        for row in report["comparisons"]:
            if row["enrollment_sessions"] == 2:
                print(row["method"], row["policy"], row["test"])


if __name__ == "__main__":
    main()
