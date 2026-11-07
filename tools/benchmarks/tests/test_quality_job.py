"""Synthetic pulled jobs only: no user audio, Android, model inference or network."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import wave

SPEC = importlib.util.spec_from_file_location("quality_job", Path(__file__).resolve().parents[1] / "verify_quality_job.py")
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class QualityJobTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.job = Path(self.tmp.name) / "job"
        self.job.mkdir()
        samples = bytes(range(256)) * 50  # 6400 PCM16 frames, four 100ms chunks.
        self.wav("original.wav", samples)
        self.plan = {"sampleCount": 6400, "chunks": []}
        stages = {name: dict(stage=name, requestId=name + "-nonce", completed=True, error=None,
                            elapsedMs=10, peakCombinedRssBytes=150_000_000, warnings=[], candidates=[], choices=[])
                  for name in validator.STAGES}
        rows = []
        for index in range(4):
            ident, relative = f"chunk_{index:06d}", f"chunks/{index}.wav"
            self.wav(relative, samples[index * 3200:(index + 1) * 3200])
            input_sha = validator.sha(self.job / relative)
            self.plan["chunks"].append(dict(id=ident, fromSample=index * 1600, toSample=(index + 1) * 1600,
                                             wav=relative, inputSha256=input_sha))
            originals = {}
            for stage, source in (("GIGAAM", "gigaam"), ("WHISPER", "whisper")):
                name = f"raw/{source}/{ident}.json"
                candidate = dict(schema_version=1, chunk_id=ident, source=source, input_sha256=input_sha,
                                 text=f"{source}-{index}. ", tokens=[], words=[])
                self.put(name, candidate)
                ref = dict(chunkId=ident, source=source, file=name, sha256=validator.sha(self.job / name))
                stages[stage]["candidates"].append(ref)
                originals[source] = ref
            original = originals["gigaam"]
            copy = f"selection/{ident}/display-candidate.json"
            self.path(copy).write_bytes(self.path(original["file"]).read_bytes())
            selected = dict(original, file=copy)
            choice = dict(chunkId=ident, candidate=selected, status="SELECTED_BY_LLM", reason="valid_local_tool_call")
            stages["SELECT"]["choices"].append(choice)
            response = f"selection/{ident}/selector.stdout"
            self.put(response, dict(tool="select_asr_variant", chunk_id=ident, source="gigaam"))
            self.put(f"selection/{ident}/audit.json", dict(chunkId=ident, source="gigaam", decision="tool",
                inputSha256=input_sha, candidateSha256=original["sha256"], selectedSha256=selected["sha256"],
                toolResponseSha256=validator.sha(self.path(response)), selectedByLlm=True, qualityValidated=False))
            rows.append(dict(speaker_id="speaker_1", start_ms=index * 100, end_ms=(index + 1) * 100, text=f"gigaam-{index}. "))
        self.put("chunks.json", self.plan)
        self.put("selection.json", stages["SELECT"]["choices"])
        for name, result in stages.items():
            self.put(f"stages/{name}.result.json", result)
            self.put(f"stages/{name}.request.json", dict(stage=name, requestId=result["requestId"], inputWav="original.wav"))
        self.put("transcript.json", dict(transcript=rows))
        self.put("transcript.metadata.json", dict(transcript=[dict(row, emotion_probabilities=[1, 0, 0, 0]) for row in rows]))
        self.report = dict(schemaVersion=2, audioMs=400, processingMs=700, modelProfile="synthetic",
            coverage=dict(expectedChunks=4, gigaamChunks=4, whisperChunks=4, selectedChunks=4, llmSelectedChunks=4, unresolvedChunks=0),
            complete=True, selectionResolved=True, fullProfileCompleted=True, peakRssBytes=200_000_000,
            reserveBytes=validator.RESERVE, budgetBytes=validator.BUDGET, stageMs={name: 10 for name in stages},
            stages=list(stages.values()), transcriptSha256=validator.sha(self.path("transcript.json")),
            metadataSha256=validator.sha(self.path("transcript.metadata.json")), warnings=[])
        self.put("completion.json", self.report)

    def path(self, name):
        path = self.job / name
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def put(self, name, value):
        self.path(name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def wav(self, name, samples):
        with wave.open(str(self.path(name)), "wb") as output:
            output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000); output.writeframes(samples)

    def test_full_job_validates_without_text_in_summary(self):
        report = validator.verify_job(self.job)
        self.assertTrue(report["valid"], report["errors"])
        self.assertTrue(report["quality_success"])
        self.assertEqual(report["counts"]["llm_selected_chunks"], 4)
        self.assertNotIn("gigaam-0. ", json.dumps(report))

    def test_adaptive_equivalence_requires_both_verified_original_texts(self):
        profile = dict(requestedMode="QUALITY", mode="QUALITY", denoise=True,
                       gigaWorkers=2, whisperWorkers=1, gigaThreads=2, whisperThreads=4)
        self.put("processing-profile.json", profile)
        self.report.update(schemaVersion=3, processingProfile=profile, skippedSilenceMs=0, localLlmUsed=True)
        self.report["coverage"].update(equivalentChunks=1, llmSelectedChunks=3)
        whisper = json.loads(self.path("stages/WHISPER.result.json").read_text())
        ref = whisper["candidates"][0]
        value = json.loads(self.path(ref["file"]).read_text())
        value["text"] = "gigaam-0. "
        self.put(ref["file"], value)
        ref["sha256"] = validator.sha(self.path(ref["file"]))
        selection = json.loads(self.path("stages/SELECT.result.json").read_text())
        selection["choices"][0].update(status="EQUIVALENT", reason="identical_original_texts")
        audit = json.loads(self.path("selection/chunk_000000/audit.json").read_text())
        audit.update(decision="exact_text_equality", selectedByLlm=False, toolResponseSha256=None)
        self.put("selection/chunk_000000/audit.json", audit)
        self.put("selection.json", selection["choices"])
        self.put("stages/SELECT.result.json", selection)
        self.put("stages/WHISPER.result.json", whisper)
        self.report["stages"] = [selection if s["stage"] == "SELECT" else whisper if s["stage"] == "WHISPER" else s
                                 for s in self.report["stages"]]
        self.put("completion.json", self.report)
        checked = validator.verify_job(self.job)
        self.assertTrue(checked["valid"], checked["errors"])
        self.assertEqual(1, checked["counts"]["equivalent_chunks"])
        value["text"] += "Different."
        self.put(ref["file"], value)
        ref["sha256"] = validator.sha(self.path(ref["file"]))
        self.put("stages/WHISPER.result.json", whisper)
        self.put("completion.json", self.report)
        self.assertIn("unequal_texts_claimed_equivalent", validator.verify_job(self.job)["errors"])

    def test_tampered_selected_copy_is_rejected_even_when_its_reference_hash_is_updated(self):
        choices = json.loads(self.path("selection.json").read_text(encoding="utf-8"))
        ref = choices[0]["candidate"]
        self.path(ref["file"]).write_bytes(self.path(ref["file"]).read_bytes() + b" ")
        ref["sha256"] = validator.sha(self.path(ref["file"]))
        self.put("selection.json", choices)
        stage = json.loads(self.path("stages/SELECT.result.json").read_text(encoding="utf-8"))
        stage["choices"] = choices
        self.put("stages/SELECT.result.json", stage)
        self.assertIn("selected_copy_not_byte_identical", validator.verify_job(self.job)["errors"])

    def test_float_timestamp_is_rejected(self):
        transcript = json.loads(self.path("transcript.json").read_text(encoding="utf-8"))
        transcript["transcript"][0]["start_ms"] = 0.0
        self.put("transcript.json", transcript)
        self.assertIn("invalid_submission_timestamps", validator.verify_job(self.job)["errors"])

    def test_missing_llm_evidence_and_overbudget_fail_closed(self):
        self.path("selection/chunk_000000/selector.stdout").unlink()
        self.assertFalse(validator.verify_job(self.job)["valid"])
        self.report["peakRssBytes"] = validator.BUDGET - validator.RESERVE + 1
        self.put("completion.json", self.report)
        self.assertIn("memory_budget_exceeded_or_underreported", validator.verify_job(self.job)["errors"])

    def test_pcm_tampering_cannot_be_hidden_by_changing_only_chunk_wav_hash(self):
        chunk = self.plan["chunks"][0]
        self.wav(chunk["wav"], b"\0" * 3200)
        chunk["inputSha256"] = validator.sha(self.path(chunk["wav"]))
        self.put("chunks.json", self.plan)
        self.assertIn("chunk_pcm_not_exact_input_view", validator.verify_job(self.job)["errors"])

    def test_terminal_memory_rejection_overrides_success_checkpoint(self):
        stage = json.loads(self.path("stages/WHISPER.result.json").read_text(encoding="utf-8"))
        self.put("stages/WHISPER.terminal.json", dict(stage, completed=False, error="MEMORY_BUDGET_EXCEEDED"))
        self.assertIn("stage_incomplete:WHISPER", validator.verify_job(self.job)["errors"])

    def test_legacy_export_absence_requires_explicit_opt_in(self):
        self.put("completion.json", dict(complete="true", warnings="", peak_rss_bytes="200000000"))
        self.path("transcript.json").unlink(); self.path("transcript.metadata.json").unlink()
        self.assertFalse(validator.verify_job(self.job)["valid"])
        report = validator.verify_job(self.job, allow_legacy=True)
        self.assertTrue(report["valid"], report["errors"])
        self.assertFalse(report["submission_export_verified"])
        self.assertFalse(report["quality_success"])

    def test_unresolved_copy_is_not_misreported_as_quality_success(self):
        choices = json.loads(self.path("selection.json").read_text(encoding="utf-8"))
        choices[0]["status"] = "UNRESOLVED"
        self.put("selection.json", choices)
        stage = json.loads(self.path("stages/SELECT.result.json").read_text(encoding="utf-8"))
        stage["choices"] = choices
        self.put("stages/SELECT.result.json", stage)
        audit = json.loads(self.path("selection/chunk_000000/audit.json").read_text(encoding="utf-8"))
        audit["selectedByLlm"] = False
        self.put("selection/chunk_000000/audit.json", audit)
        self.report["coverage"].update(llmSelectedChunks=3, unresolvedChunks=1)
        self.report.update(selectionResolved=False, fullProfileCompleted=False)
        self.put("completion.json", self.report)
        report = validator.verify_job(self.job)
        self.assertFalse(report["valid"])
        self.assertFalse(report["quality_success"])
        self.assertEqual(report["counts"]["unresolved_chunks"], 1)

    def test_llm_response_sha_and_duplicate_keys_are_checked(self):
        response = self.path("selection/chunk_000000/selector.stdout")
        raw = response.read_text(encoding="utf-8")
        response.write_text(" " + raw, encoding="utf-8")
        self.assertIn("llm_audit_sha_mismatch", validator.verify_job(self.job)["errors"])
        response.write_text(raw.replace('{', '{"tool":"wrong",', 1), encoding="utf-8")
        audit = json.loads(self.path("selection/chunk_000000/audit.json").read_text(encoding="utf-8"))
        audit["toolResponseSha256"] = validator.sha(response)
        self.put("selection/chunk_000000/audit.json", audit)
        self.assertIn("duplicate_json_key", validator.verify_job(self.job)["errors"])

    def test_malformed_top_level_report_fails_with_sanitized_summary(self):
        self.put("completion.json", [])
        report = validator.verify_job(self.job)
        self.assertFalse(report["valid"])
        self.assertEqual(report["errors"], ["missing_or_malformed_job_artifact"])

    def test_russian_and_belarusian_records_keep_the_full_profile(self):
        for code in ("ru", "be"):
            self.put("language.json", dict(code=code, route="slavic", samples=6400, detected=True))
            report = validator.verify_job(self.job)
            self.assertTrue(report["valid"], report["errors"])

    def test_foreign_language_accepts_whisper_only_without_claiming_the_full_profile(self):
        self.put("language.json", dict(code="en", route="whisper_only", samples=6400, detected=True))
        for name in ("GIGAAM", "SELECT"):
            self.path(f"stages/{name}.result.json").unlink()
            self.path(f"stages/{name}.request.json").unlink()
        whisper = json.loads(self.path("stages/WHISPER.result.json").read_text(encoding="utf-8"))
        choices, rows = [], []
        for index, ref in enumerate(whisper["candidates"]):
            ident = ref["chunkId"]
            original = json.loads(self.path(ref["file"]).read_text(encoding="utf-8"))
            copy = f"selection/{ident}/display-candidate.json"
            self.path(copy).write_bytes(self.path(ref["file"]).read_bytes())
            selected = dict(ref, file=copy, sha256=validator.sha(self.path(copy)))
            choices.append(dict(chunkId=ident, candidate=selected, status="ONE_AVAILABLE", reason="language_route_whisper_only"))
            self.put(f"selection/{ident}/audit.json", dict(chunkId=ident, source="whisper", decision="unconfirmed_display_fallback",
                reason="language_route_whisper_only", inputSha256=original["input_sha256"], candidateSha256=ref["sha256"],
                selectedSha256=selected["sha256"], selectedByLlm=False, qualityValidated=False))
            rows.append(dict(speaker_id="speaker_1", start_ms=index * 100, end_ms=(index + 1) * 100, text=f"whisper-{index}. "))
        self.put("selection.json", choices)
        self.put("transcript.json", dict(transcript=rows))
        self.put("transcript.metadata.json", dict(transcript=[dict(row, emotion_probabilities=[1, 0, 0, 0]) for row in rows]))
        self.report["stages"] = [stage for stage in self.report["stages"] if stage["stage"] not in ("GIGAAM", "SELECT")]
        self.report["coverage"] = dict(expectedChunks=4, gigaamChunks=0, whisperChunks=4, selectedChunks=4, llmSelectedChunks=0, unresolvedChunks=0)
        self.report.update(fullProfileCompleted=False, warnings=["LANGUAGE:en", "ASR_ROUTE:whisper_only:en"],
                           transcriptSha256=validator.sha(self.path("transcript.json")),
                           metadataSha256=validator.sha(self.path("transcript.metadata.json")))
        self.put("completion.json", self.report)
        report = validator.verify_job(self.job)
        self.assertTrue(report["valid"], report["errors"])
        self.assertTrue(report["quality_success"])
        self.assertEqual(report["counts"]["whisper_chunks"], 4)
        self.assertEqual(report["counts"]["gigaam_chunks"], 0)
        self.assertEqual(report["counts"]["one_available_chunks"], 4)
        self.assertNotIn("whisper-0. ", json.dumps(report))

    def test_russian_cannot_be_marked_whisper_only(self):
        self.put("language.json", dict(code="ru", route="whisper_only", samples=6400, detected=True))
        self.assertIn("whisper_only_route_inconsistent", validator.verify_job(self.job)["errors"])

    def test_cli_returns_nonzero_and_writes_sanitized_failure_report(self):
        self.path("selection/chunk_000000/audit.json").unlink()
        output = Path(self.tmp.name) / "validation.json"
        with contextlib.redirect_stdout(io.StringIO()):
            code = validator.main(["--job-dir", str(self.job), "--output", str(output)])
        self.assertEqual(code, 2)
        self.assertFalse(json.loads(output.read_text())["valid"])


if __name__ == "__main__":
    unittest.main()
