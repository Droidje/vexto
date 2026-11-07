"""Read-only consistency validator for a pulled Android quality job; no inference.

Quality success means a complete, bounded, grounded pipeline run, not measured
ASR/diarization accuracy. The JSON summary never contains recognized text/audio.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import wave

BUDGET, RESERVE = 2_000_000_000, 128 * 1024 * 1024
STAGES = ("DENOISE", "DIARIZE", "VOICEPRINT", "GIGAAM", "WHISPER", "SELECT", "EMOTION")
FIELDS = {"speaker_id", "start_ms", "end_ms", "text"}


class InvalidJob(ValueError):
    pass


def check(condition, code):
    if not condition:
        raise InvalidJob(code)


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def parse_json(text):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            check(key not in result, "duplicate_json_key")
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=unique,
                      parse_constant=lambda _: check(False, "nonfinite_json_number"))


def document(path):
    return parse_json(path.read_text(encoding="utf-8"))


def inside(root, name):
    check(isinstance(name, str) and name and "\\" not in name and ":" not in name, "unsafe_artifact_path")
    path = (root / name).resolve()
    check(root in path.parents, "artifact_outside_job")
    return path


def pcm(path, joined=None):
    with wave.open(str(path), "rb") as source:
        check((source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) ==
              (1, 2, 16000, "NONE"), "invalid_pcm_format")
        digest, count = hashlib.sha256(), 0
        for block in iter(lambda: source.readframes(8192), b""):
            digest.update(block)
            if joined is not None:
                joined.update(block)
            count += len(block)
        check(count == source.getnframes() * 2, "truncated_pcm")
        return source.getnframes(), digest.hexdigest()


def canonical_chunk_sha(source_path, start, end, joined):
    """Match Android PcmWave.cropSha256: canonical 44-byte header + exact PCM slice."""
    size = (end - start) * 2
    check(0 < size <= 0xffffffff - 36, "invalid_virtual_chunk_length")
    header = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", size + 36, b"WAVE", b"fmt ",
                         16, 1, 1, 16000, 32000, 2, 16, b"data", size)
    digest = hashlib.sha256(header)
    with wave.open(str(source_path), "rb") as source:
        check((source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) ==
              (1, 2, 16000, "NONE") and 0 <= start < end <= source.getnframes(), "invalid_virtual_chunk_range")
        source.setpos(start)
        remaining = end - start
        while remaining:
            count = min(8192, remaining)
            block = source.readframes(count)
            check(len(block) == count * 2, "truncated_virtual_chunk_pcm")
            digest.update(block)
            joined.update(block)
            remaining -= count
    return digest.hexdigest()


def language_record(root):
    path = root / "language.json"
    if not path.is_file():
        return None
    value = document(path)
    check(set(value) == {"code", "route", "samples", "detected"}, "invalid_language_record")
    code, route = value["code"], value["route"]
    check(isinstance(code, str) and re.fullmatch(r"[a-z]{2,3}", code) is not None, "invalid_language_code")
    check(route in ("slavic", "whisper_only") and type(value["detected"]) is bool and integer(value["samples"]), "invalid_language_route")
    if route == "whisper_only":
        check(code not in ("ru", "be") and value["detected"] is True, "whisper_only_route_inconsistent")
    else:
        check(code in ("ru", "be") or value["detected"] is False, "slavic_route_inconsistent")
    return value


def verify_job(job_dir, allow_legacy=False):
    root = Path(job_dir).resolve()
    summary = {"schema_version": 1, "valid": False, "quality_success": False,
               "submission_export_verified": False, "metadata_verified": False,
               "retained_audio_bytes": 0, "materialized_chunk_wavs": 0,
               "counts": {}, "timings_ms": {}, "hashes": {}, "errors": [],
               "memory_note": "Observed Android app RSS plus reserve; sampling is not a hard allocation cap"}
    try:
        summary["retained_audio_bytes"] = sum(path.stat().st_size for path in root.rglob("*")
            if not path.is_symlink() and path.is_file() and path.suffix.lower() in (".wav", ".pcm"))
        frames, _ = pcm(root / "original.wav")
        check(frames > 0, "empty_recording")
        summary["audio_samples"] = frames
        summary["hashes"]["original_wav"] = sha(root / "original.wav")
        report = document(root / "completion.json")
        modern = type(report.get("schemaVersion")) is int and report["schemaVersion"] == 2
        check(modern or (allow_legacy and set(report) == {"complete", "warnings", "peak_rss_bytes"}
                         and all(isinstance(value, str) for value in report.values())), "legacy_report_requires_explicit_opt_in")
        summary["source_report_version"] = 2 if modern else "legacy"
        plan = document(root / "chunks.json")
        check(type(plan["sampleCount"]) is int and plan["sampleCount"] == frames, "plan_sample_count_mismatch")
        request_path = root / "stages/GIGAAM.request.json"
        requested_view = document(request_path)["inputWav"] if request_path.exists() else None
        storage_path = root / "chunk-storage.json"
        storage = document(storage_path) if storage_path.exists() else None
        if storage is not None:
            check(set(storage) == {"schemaVersion", "mode", "inputWav", "inputSha256"} and
                  type(storage["schemaVersion"]) is int and storage["schemaVersion"] == 1 and
                  storage["mode"] == "on-demand", "invalid_chunk_storage_policy")
            view = storage["inputWav"]
            check(requested_view is None or requested_view == view, "chunk_storage_request_view_mismatch")
            source_path = inside(root, view)
            check(isinstance(storage["inputSha256"], str) and
                  re.fullmatch("[0-9a-f]{64}", storage["inputSha256"]) is not None and
                  sha(source_path) == storage["inputSha256"], "chunk_storage_source_sha_mismatch")
            whisper_request = root / "stages/WHISPER.request.json"
            if whisper_request.exists():
                check(document(whisper_request)["inputWav"] == view, "chunk_storage_request_view_mismatch")
        else:
            view = requested_view or ("denoised.wav" if (root / "denoised.wav").exists() else "original.wav")
            source_path = inside(root, view)
        view_frames, view_pcm = pcm(source_path)
        check(view_frames == frames, "input_view_sample_count_mismatch")
        chunks, cursor, joined = {}, 0, hashlib.sha256()
        for index, chunk in enumerate(plan["chunks"]):
            ident = chunk["id"]
            check(ident == f"chunk_{index:06d}" and ident not in chunks, "invalid_chunk_id")
            check(integer(chunk["fromSample"]) and integer(chunk["toSample"]) and
                  chunk["fromSample"] == cursor < chunk["toSample"] <= cursor + 160000, "chunk_coverage_gap_or_overlap")
            path = inside(root, chunk["wav"])
            if path.is_file():
                count, _ = pcm(path, joined)
                check(count == chunk["toSample"] - cursor and sha(path) == chunk["inputSha256"], "chunk_audio_hash_or_length_mismatch")
                summary["materialized_chunk_wavs"] += 1
            else:
                check(storage is not None and not path.exists(), "missing_chunk_wav_without_on_demand_policy")
                check(canonical_chunk_sha(source_path, cursor, chunk["toSample"], joined) == chunk["inputSha256"],
                      "virtual_chunk_canonical_sha_mismatch")
            chunks[ident], cursor = chunk, chunk["toSample"]
        check(cursor == frames and chunks, "incomplete_sample_coverage")
        summary["counts"]["expected_chunks"] = len(chunks)
        check(view_pcm == joined.hexdigest(), "chunk_pcm_not_exact_input_view")
        recorded = language_record(root)
        whisper_only = recorded is not None and recorded["route"] == "whisper_only"
        required_stages = tuple(name for name in STAGES if name not in (("GIGAAM", "SELECT") if whisper_only else ()))
        if whisper_only:
            check(not (root / "stages/GIGAAM.result.json").exists() and not (root / "stages/SELECT.result.json").exists(),
                  "gigaam_present_for_foreign_language")
        stages = {}
        for name in required_stages:
            stage = document(root / f"stages/{name}.result.json")
            request_file = root / f"stages/{name}.request.json"
            if request_file.exists():
                request = document(request_file)
                check(stage["requestId"] == request["requestId"] and request["stage"] == name, "stage_request_nonce_mismatch")
            terminal = root / f"stages/{name}.terminal.json"
            if terminal.exists():
                value = document(terminal)
                if value.get("requestId") == stage.get("requestId") and value.get("stage") == name:
                    stage = value
            check(stage["stage"] == name and stage.get("completed") is True and stage.get("error") is None, "stage_incomplete:" + name)
            check(isinstance(stage.get("requestId"), str) and stage["requestId"], "missing_stage_nonce")
            check(integer(stage["elapsedMs"]) and integer(stage["peakCombinedRssBytes"], 1), "invalid_stage_measurements")
            check(not any("_FAILED:" in warning or warning.startswith(("ASR_MISSING:", "INVALID_")) for warning in stage.get("warnings", [])), "stage_chunk_failure:" + name)
            stages[name] = stage
            summary["timings_ms"][name] = stage["elapsedMs"]
        peak = report["peakRssBytes"] if modern else int(report["peak_rss_bytes"])
        reserve, budget = (report["reserveBytes"], report["budgetBytes"]) if modern else (RESERVE, BUDGET)
        check(integer(peak, 1) and integer(reserve) and integer(budget) and reserve == RESERVE and budget == BUDGET and
              max(stage["peakCombinedRssBytes"] for stage in stages.values()) <= peak and peak + reserve <= BUDGET, "memory_budget_exceeded_or_underreported")
        summary.update(peak_rss_bytes=peak, reserve_bytes=reserve, budget_bytes=budget)

        def candidate(ref):
            check(ref["chunkId"] in chunks and ref["source"] in ("gigaam", "whisper"), "invalid_candidate_reference")
            path = inside(root, ref["file"])
            check(re.fullmatch("[0-9a-f]{64}", ref["sha256"]) is not None and sha(path) == ref["sha256"], "candidate_sha_mismatch")
            value = document(path)
            check(set(value) == {"schema_version", "chunk_id", "source", "input_sha256", "text", "tokens", "words"}
                  and type(value["schema_version"]) is int and value["schema_version"] == 1, "invalid_candidate_schema")
            check(value["chunk_id"] == ref["chunkId"] and value["source"] == ref["source"] and
                  value["input_sha256"] == chunks[ref["chunkId"]]["inputSha256"] and isinstance(value["text"], str), "candidate_input_binding_mismatch")
            return path, value

        originals = {}
        asr_stages = (("WHISPER", "whisper"),) if whisper_only else (("GIGAAM", "gigaam"), ("WHISPER", "whisper"))
        for stage, source in asr_stages:
            for ref in stages[stage]["candidates"]:
                path, value = candidate(ref)
                key = (ref["chunkId"], ref["source"])
                check(ref["source"] == source and key not in originals, "duplicate_or_wrong_source_candidate")
                originals[key] = (path, ref["sha256"])
            summary["counts"][source + "_chunks"] = sum(key[1] == source for key in originals)
        if whisper_only:
            summary["counts"]["gigaam_chunks"] = 0
        check(len(originals) == (len(chunks) if whisper_only else 2 * len(chunks)), "missing_raw_asr_candidate")
        choices = document(root / "selection.json")
        if whisper_only:
            check(all(choice["status"] == "ONE_AVAILABLE" and choice["reason"] == "language_route_whisper_only"
                      and choice["candidate"]["source"] == "whisper" for choice in choices),
                  "foreign_language_selection_not_whisper_only")
        else:
            check(choices == stages["SELECT"]["choices"], "selection_journal_mismatch")
        selected, llm, unresolved, one_available = {}, 0, 0, 0
        for choice in choices:
            ident, ref = choice["chunkId"], choice["candidate"]
            check(ident == ref["chunkId"] and ident not in selected, "duplicate_selection_or_chunk_mismatch")
            path, value = candidate(ref)
            original, original_sha = originals[(ident, ref["source"])]
            check(path.read_bytes() == original.read_bytes(), "selected_copy_not_byte_identical")
            audit = document(root / f"selection/{ident}/audit.json")
            check(audit["chunkId"] == ident and audit["source"] == ref["source"] and
                  audit["inputSha256"] == chunks[ident]["inputSha256"] and
                  audit["candidateSha256"] == original_sha and audit["selectedSha256"] == ref["sha256"], "selection_audit_binding_mismatch")
            if choice["status"] == "SELECTED_BY_LLM":
                stdout = root / f"selection/{ident}/selector.stdout"
                check(audit["selectedByLlm"] is True and audit["decision"] == "tool" and
                      audit["toolResponseSha256"] == sha(stdout), "llm_audit_sha_mismatch")
                response_kind = audit.get("responseKind", "tool_json_v1")
                check(response_kind in ("tool_json_v1", "scoped_source_v1"), "unknown_llm_response_kind")
                raw = stdout.read_text(encoding="utf-8")
                whitespace = " \t\r\n" if response_kind == "scoped_source_v1" else None
                raw = raw.strip(whitespace)
                if raw.endswith("[end of text]"):
                    raw = raw[:-13].rstrip(whitespace)
                call = parse_json(raw)
                if response_kind == "scoped_source_v1":
                    check(type(call) is str and call in ("gigaam", "whisper") and call == ref["source"], "invalid_llm_scoped_source")
                    bound = root / f"selection/{ident}/bound-tool-call.json"
                    check(audit.get("scopedToolCallSha256") == sha(bound), "llm_scoped_call_sha_mismatch")
                    call = document(bound)
                check(call == {"tool": "select_asr_variant", "chunk_id": ident, "source": ref["source"]}, "invalid_llm_local_tool_call")
                check(audit.get("qualityValidated", False) is False, "unsubstantiated_quality_claim")
                llm += 1
            else:
                check(choice["status"] in ("UNRESOLVED", "ONE_AVAILABLE") and audit["selectedByLlm"] is False, "invalid_selection_status")
                unresolved += choice["status"] == "UNRESOLVED"
                one_available += choice["status"] == "ONE_AVAILABLE"
            selected[ident] = value["text"]
        summary["counts"].update(selected_chunks=len(selected), llm_selected_chunks=llm, unresolved_chunks=unresolved, one_available_chunks=one_available)
        check(set(selected) == set(chunks), "missing_selected_chunk")
        export = root / "transcript.json"
        if export.exists():
            transcript = document(export)
            check(set(transcript) == {"transcript"} and isinstance(transcript["transcript"], list), "invalid_submission_schema")
            previous = 0
            for row in transcript["transcript"]:
                check(set(row) == FIELDS and isinstance(row["speaker_id"], str) and row["speaker_id"].strip() and isinstance(row["text"], str), "invalid_submission_row_fields")
                check(integer(row["start_ms"]) and integer(row["end_ms"]) and previous <= row["start_ms"] < row["end_ms"] <= (frames + 15) // 16, "invalid_submission_timestamps")
                previous = row["start_ms"]
            check("".join(row["text"] for row in transcript["transcript"]) == "".join(selected[key] for key in chunks), "submission_raw_text_loss")
            summary["submission_export_verified"] = True
            summary["hashes"]["transcript"] = sha(export)
        else:
            check(allow_legacy and not modern, "missing_submission_export")
        if modern:
            metadata = root / "transcript.metadata.json"
            check(sha(export) == report["transcriptSha256"] and sha(metadata) == report["metadataSha256"], "report_export_hash_mismatch")
            check([{key: row[key] for key in FIELDS} for row in document(metadata)["transcript"]] == transcript["transcript"], "metadata_transcript_mismatch")
            summary["metadata_verified"] = True
            summary["hashes"]["metadata"] = sha(metadata)
            check(integer(report["audioMs"]) and report["audioMs"] == frames // 16 and integer(report["processingMs"], 1), "invalid_report_timings")
            if whisper_only:
                expected = dict(expectedChunks=len(chunks), gigaamChunks=0, whisperChunks=len(chunks), selectedChunks=len(selected), llmSelectedChunks=0, unresolvedChunks=0)
                check(report["complete"] is True and report["selectionResolved"] is True and report["fullProfileCompleted"] is False, "foreign_language_profile_flags")
                check(f"ASR_ROUTE:whisper_only:{recorded['code']}" in report["warnings"], "missing_whisper_only_route_warning")
            else:
                expected = dict(expectedChunks=len(chunks), gigaamChunks=len(chunks), whisperChunks=len(chunks), selectedChunks=len(selected), llmSelectedChunks=llm, unresolvedChunks=unresolved)
                check(report["complete"] is True and report["selectionResolved"] is True and report["fullProfileCompleted"] is True, "reported_profile_incomplete")
            check(all(type(value) is int for value in report["coverage"].values()) and report["coverage"] == expected, "report_coverage_mismatch")
            summary["processing_ms"] = report["processingMs"]
            check(not any("_FAILED:" in warning or warning.startswith(("ASR_MISSING:", "INVALID_")) for warning in report["warnings"]), "report_contains_failure_warning")
            check({stage["stage"] for stage in report["stages"]} == set(required_stages) and len(report["stages"]) == len(required_stages), "report_stage_set_mismatch")
            check(all(stage["requestId"] == stages[stage["stage"]]["requestId"] and stage["completed"] is True and stage.get("error") is None for stage in report["stages"]), "report_stage_outcome_mismatch")
        else:
            check(report["complete"] == "true" and not any(marker in report["warnings"] for marker in ("_FAILED:", "ASR_MISSING:", "INVALID_")), "legacy_incomplete")
        if whisper_only:
            check(llm == 0 and one_available == len(chunks) and unresolved == 0, "foreign_language_selection_not_whisper_only")
        else:
            check(llm == len(chunks), "llm_selection_not_fully_confirmed")
        summary["valid"] = True
        summary["quality_success"] = summary["submission_export_verified"] and (not modern or summary["metadata_verified"])
    except InvalidJob as error:
        summary["errors"] = [str(error)]
    except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError, wave.Error):
        summary["errors"] = ["missing_or_malformed_job_artifact"]
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--job-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-legacy", action="store_true")
    args = parser.parse_args(argv)
    if args.output.resolve().is_relative_to(args.job_dir.resolve()):
        parser.error("Place the summary outside the read-only job directory")
    report = verify_job(args.job_dir, args.allow_legacy)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report["valid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
