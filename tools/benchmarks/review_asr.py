"""Prepare bounded ASR comparison prompts and validate optional model suggestions.

No model inference and no transcript rewriting. Run the generated prompt with a
local Android LLM separately. RAW ASR outputs remain read-only.
"""
import argparse
import difflib
import hashlib
import json
import re
import unicodedata
from pathlib import Path


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    def constant(value):
        raise ValueError("nonfinite JSON constant")
    return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)


def read_text(path):
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("input exceeds 16 MiB diagnostic limit")
    return path.read_text(encoding="utf-8-sig")


def asr_text(content):
    """Accept one Sherpa JSON result/line or a full whisper.cpp JSON result."""
    def extract(value):
        if not isinstance(value, dict):
            return None
        if isinstance(value.get("text"), str):
            return value["text"]
        segments = value.get("transcription")
        if isinstance(segments, list) and all(isinstance(s, dict) and isinstance(s.get("text"), str) for s in segments):
            return " ".join(s["text"].strip() for s in segments)
        return None
    try:
        result = extract(strict_json(content))
    except ValueError:
        result = None
    if result is not None:
        return result
    found = []
    for line in content.splitlines():
        try:
            value = extract(strict_json(line))
        except ValueError:
            continue
        if value is not None:
            found.append(value)
    if len(found) != 1:
        raise ValueError("expected one ASR JSON result; diagnostic logs are not plain transcript")
    return found[0]


def word_spans(text):
    """Unicode letters/numbers/marks, retaining internal apostrophes/hyphens."""
    def char(c):
        return c == "_" or unicodedata.category(c)[0] in "LNM"
    spans = []; i = 0
    while i < len(text):
        if not char(text[i]):
            i += 1; continue
        start = i; i += 1
        while i < len(text):
            if char(text[i]):
                i += 1
            elif text[i] in "-'’" and i + 1 < len(text) and char(text[i + 1]):
                i += 1
            else:
                break
        spans.append((start, i, text[start:i]))
    return spans


def fragments(primary, secondary, words=50, maximum=3):
    if not 1 <= words <= 50 or not 1 <= maximum <= 10:
        raise ValueError("fragments require 1..50 words and 1..10 fragments")
    a, b = word_spans(primary), word_spans(secondary)
    changes = [op for op in difflib.SequenceMatcher(None, [x[2] for x in a], [x[2] for x in b]).get_opcodes() if op[0] != "equal"]
    if not changes:
        changes = [("equal", 0, 0, 0, 0)]
    result = []; seen = set()
    for tag, i, end, j, other_end in changes:
        left = max(0, min(i - words // 3, max(0, len(a) - words)))
        right = min(len(a), left + words)
        if (left, right) in seen:
            continue
        seen.add((left, right))
        other_left = max(0, min(j - words // 3, max(0, len(b) - words)))
        other_right = min(len(b), other_left + words)
        start_char = a[left][0] if a else 0
        end_char = a[right - 1][1] if right else len(primary)
        # Keep outer punctuation when the chosen window reaches the text edge.
        if left == 0: start_char = 0
        if right == len(a): end_char = len(primary)
        second = secondary[b[other_left][0]:b[other_right - 1][1]] if other_right else ""
        result.append({"fragment_id": len(result) + 1, "primary": primary[start_char:end_char],
            "secondary": second, "primary_char_start": start_char, "primary_char_end": end_char,
            "primary_word_count": right - left, "secondary_word_count": other_right - other_left,
            "difference_kind": tag})
        if len(result) >= maximum:
            break
    return result


def validate_review(fragment, response):
    raw = fragment["primary"]; audit = {"raw_asr_rewritten": False, "automatic_lexical_replacements": False,
        "punctuation_accepted": False, "rejections": [], "parse_error": None}
    result = {"fragment_id": fragment["fragment_id"], "punctuated_text": None, "replacement_proposals": []}
    marker = "[end of text]"; response = response.strip()
    if response.endswith(marker): response = response[:-len(marker)].strip()
    try:
        value = strict_json(response)
        if not isinstance(value, dict) or set(value) != {"punctuated_text", "replacements"}:
            raise ValueError("expected only punctuated_text and replacements")
        punctuation = value["punctuated_text"]
        protected = lambda text: "".join(c for c in text if not c.isspace() and unicodedata.category(c)[0] != "P")
        if (isinstance(punctuation, str) and [x[2] for x in word_spans(punctuation)] == [x[2] for x in word_spans(raw)]
                and protected(punctuation) == protected(raw)):
            result["punctuated_text"] = punctuation; audit["punctuation_accepted"] = True
        else:
            audit["rejections"].append({"field": "punctuated_text", "reason": "words_or_case_changed"})
        if not isinstance(value["replacements"], list):
            raise ValueError("replacements must be an array")
        seen = set()
        for index, proposal in enumerate(value["replacements"]):
            reason = None
            if not isinstance(proposal, dict) or set(proposal) != {"original", "replacement", "reason"} or not all(isinstance(v, str) for v in proposal.values()):
                reason = "invalid_proposal_schema"
            else:
                original, replacement = proposal["original"], proposal["replacement"]
                positions = [(a, b) for a, b, token in word_spans(raw)]
                matches = [m for m in re.finditer(re.escape(original), raw)] if original else []
                literal = any(any(a == m.start() for a, _ in positions) and any(b == m.end() for _, b in positions) for m in matches)
                key = (original, replacement)
                if not literal or not 1 <= len(word_spans(original)) <= 10: reason = "original_not_literal_bounded_words"
                elif not 1 <= len(word_spans(replacement)) <= 10 or len(replacement) > 240: reason = "replacement_outside_bounds"
                elif not proposal["reason"].strip() or len(proposal["reason"]) > 240: reason = "reason_outside_bounds"
                elif key in seen: reason = "duplicate_proposal"
                elif len(result["replacement_proposals"]) >= 3: reason = "maximum_three_proposals"
                else: seen.add(key); result["replacement_proposals"].append(proposal)
            if reason: audit["rejections"].append({"index": index, "reason": reason})
    except ValueError as error:
        audit["parse_error"] = str(error)
    audit["proposals_applied"] = 0
    return result, audit


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("--primary", type=Path, required=True)
    prepare.add_argument("--secondary", type=Path, required=True)
    prepare.add_argument("--output-dir", type=Path, required=True)
    prepare.add_argument("--fragment-words", type=int, default=50)
    prepare.add_argument("--max-fragments", type=int, default=3)
    validate = commands.add_parser("validate")
    validate.add_argument("--context", type=Path, required=True)
    validate.add_argument("--model-output", type=Path, required=True)
    validate.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.action == "prepare":
            primary, secondary = asr_text(read_text(args.primary)), asr_text(read_text(args.secondary))
            template = (Path(__file__).parent / "templates/asr-review.prompt.txt").read_text(encoding="utf-8")
            schema = Path(__file__).parent / "templates/asr-review.schema.json"
            if args.output_dir.exists() and any(args.output_dir.iterdir()):
                raise ValueError("prepare requires an absent or empty output directory")
            args.output_dir.mkdir(parents=True, exist_ok=True)
            for fragment in fragments(primary, secondary, args.fragment_words, args.max_fragments):
                stem = args.output_dir / ("fragment-" + str(fragment["fragment_id"]))
                context = dict(fragment, primary_sha256=hashlib.sha256(args.primary.read_bytes()).hexdigest(), secondary_sha256=hashlib.sha256(args.secondary.read_bytes()).hexdigest())
                write_json(stem.with_suffix(".context.json"), context)
                rendered = re.sub(r"\{\{(PRIMARY|SECONDARY)\}\}", lambda m: fragment[m[1].lower()], template)
                stem.with_suffix(".prompt.txt").write_text(rendered, encoding="utf-8")
            (args.output_dir / "review.schema.json").write_bytes(schema.read_bytes())
        else:
            sidecar = args.output.with_name(args.output.stem + ".audit.json")
            if {args.output.resolve(), sidecar.resolve()} & {args.context.resolve(), args.model_output.resolve()}:
                raise ValueError("output must not overwrite inputs")
            result, audit = validate_review(strict_json(read_text(args.context)), read_text(args.model_output))
            write_json(args.output, result)
            write_json(sidecar, audit)
        return 0
    except (ValueError, OSError, KeyError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
