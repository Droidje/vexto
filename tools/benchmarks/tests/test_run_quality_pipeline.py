import copy
import contextlib
import io
import json
import shlex
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_quality_pipeline import argument_parser, build_plan, fully_accepted, main


class QualityPipelinePlanTests(unittest.TestCase):
    def args(self, *extra):
        return argument_parser().parse_args(["--serial", "test-device", "--remote-root", "/data/local/tmp/models",
            "--input-wav", "fixture.wav", "--output-dir", "unused-test-output", "--metadata-mode", "literal"] + list(extra))

    def command(self, step):
        if step["argv"][:2] == ["sh", "-c"]:
            tokens = shlex.split(step["argv"][2])
            self.assertEqual(tokens[-2], ">")
            return tokens[:-2], tokens[-1]
        return step["argv"], None

    def test_fresh_namespace_is_used_for_all_dataflow(self):
        args = self.args()
        plan, paths, job = build_plan(args, "session-a")
        other, other_paths, other_job = build_plan(args, "session-b")
        self.assertNotEqual(job, other_job)
        self.assertTrue(all(path.startswith(job + "/") for path in paths.values()))
        prompt = next(s for s in plan["steps"] if s["name"] == "build-prompt")["argv"]
        self.assertEqual(prompt[-4:], ["prompt", paths["asr.log"],
            "/data/local/tmp/models/pipeline2/llm/metadata-prompt.template.txt", paths["prompt.txt"]])
        llm, target = self.command(next(s for s in plan["steps"] if s["name"] == "llm-metadata"))
        self.assertEqual(llm[llm.index("-f") + 1], paths["prompt.txt"])
        self.assertEqual(target, paths["llm.log"])
        self.assertNotIn("metadata-user.txt", " ".join(llm))
        export = next(s for s in plan["steps"] if s["name"] == "export-transcript")["argv"]
        self.assertEqual(export[-5:], ["export", paths["asr.log"], paths["diar.log"], paths["transcript.json"], paths["transcript.provenance.json"]])
        self.assertTrue(all(other_job not in " ".join(s["argv"]) for s in plan["steps"]))
        self.assertEqual(len(other_paths), len(paths))

    def test_redirect_quotes_arguments_and_paths_literally(self):
        hostile = "recording ' quoted $(do-not-run); spaced.wav"
        args = self.args("--input-wav", hostile, "--remote-root", "/data/local/tmp/root with space")
        plan, paths, _ = build_plan(args, "safe")
        command, target = self.command(next(s for s in plan["steps"] if s["name"] == "giga-asr"))
        self.assertEqual(command[-1], paths["denoised.wav"])
        self.assertEqual(target, paths["asr.log"])
        self.assertIn("--feat-dim=64", command)
        self.assertIn("--num-threads=2", command)

    def test_denoise_default_routes_original_only_to_diarization(self):
        args = self.args("--whisper-model", "comparison.bin", "--metadata-mode", "none")
        plan, paths, _ = build_plan(args, "denoise")
        self.assertEqual([s["name"] for s in plan["steps"]][:2], ["denoise", "restore-denoised-length"])
        diar, _ = self.command(next(s for s in plan["steps"] if s["name"] == "diarization"))
        primary, _ = self.command(next(s for s in plan["steps"] if s["name"] == "giga-asr"))
        secondary, _ = self.command(next(s for s in plan["steps"] if s["name"] == "whisper-comparison"))
        self.assertEqual(diar[-1], "/data/local/tmp/models/fixture.wav")
        self.assertEqual(primary[-1], paths["denoised.wav"])
        self.assertEqual(secondary[secondary.index("-f") + 1], paths["denoised.wav"])
        align = plan["steps"][1]["argv"]
        self.assertEqual(align[-5:], ["align-denoised", "/data/local/tmp/models/fixture.wav", paths["denoised.raw.wav"], paths["denoised.wav"], paths["denoise.audit.json"]])
        self.assertNotIn("llm-metadata", [s["name"] for s in plan["steps"]])

    def test_no_denoise_keeps_original_asr_and_default_no_keywords(self):
        args = self.args("--no-denoise", "--metadata-mode", "none")
        plan, paths, _ = build_plan(args, "original")
        self.assertNotIn("denoised.wav", paths)
        primary, _ = self.command(next(s for s in plan["steps"] if s["name"] == "giga-asr"))
        self.assertEqual(primary[-1], "/data/local/tmp/models/fixture.wav")
        defaults = argument_parser().parse_args(["--serial", "fake", "--remote-root", "/models", "--input-wav", "x.wav", "--output-dir", "unused"])
        self.assertTrue(defaults.denoise)
        self.assertEqual(defaults.metadata_mode, "none")

    def test_emo_is_opt_in_and_uses_original_speaker_intervals_after_export(self):
        self.assertFalse(self.args().emo)
        plan, paths, job = build_plan(self.args("--emo", "--metadata-mode", "none"), "emotion")
        names = [s["name"] for s in plan["steps"]]
        self.assertGreater(names.index("emotion-analysis"), names.index("export-transcript"))
        command = next(s for s in plan["steps"] if s["name"] == "emotion-analysis")["argv"]
        self.assertEqual(command[:7], ["env", "CLASSPATH=/data/local/tmp/models/pipeline2/emo/gigaemo.jar",
            "LD_LIBRARY_PATH=/data/local/tmp/models/pipeline2/emo", "app_process",
            "-Djava.library.path=/data/local/tmp/models/pipeline2/emo", "/", "GigaEmo"])
        self.assertEqual(command[7:], ["/data/local/tmp/models/pipeline2/emo/emo.int8.onnx",
            "/data/local/tmp/models/fixture.wav", job + "/emotions.json", paths["diar.log"]])
        self.assertNotIn(paths["denoised.wav"], command)

    def test_emo_root_override_remains_literal(self):
        plan, _, _ = build_plan(self.args("--emo", "--emo-root", "/custom/emotion ' models"), "override")
        command = next(s for s in plan["steps"] if s["name"] == "emotion-analysis")["argv"]
        self.assertIn("CLASSPATH=/custom/emotion ' models/gigaemo.jar", command)
        self.assertEqual(command[7], "/custom/emotion ' models/emo.int8.onnx")

    def test_optional_emo_failure_keeps_primary_and_drops_emotion_output(self):
        with tempfile.TemporaryDirectory() as temp:
            def rejected(arguments):
                plan = json.loads(Path(arguments[arguments.index("--plan") + 1]).read_text(encoding="utf-8"))
                steps = [{"name": s["name"], "status": "failed" if s["name"] == "emotion-analysis" else "accepted",
                    "accepted": s["name"] != "emotion-analysis", "conservative_observed_peak_bytes": 1000} for s in plan["steps"]]
                destination = Path(arguments[arguments.index("--output-dir") + 1]); destination.mkdir(parents=True)
                (destination / "results.json").write_text(json.dumps({"all_steps_accepted": False, "steps": steps}), encoding="utf-8")
                return 2
            with mock.patch("run_quality_pipeline.bounded.main", side_effect=rejected), \
                    mock.patch("run_quality_pipeline.subprocess.run"), contextlib.redirect_stdout(io.StringIO()):
                result = main(["--serial", "fake", "--remote-root", "/models", "--input-wav", "synthetic.wav",
                    "--output-dir", temp, "--emo"])
            self.assertEqual(result, 0)
            summary = json.loads((Path(temp) / "pipeline.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "completed_with_optional_failures")
            self.assertIn("transcript.json", summary["artifacts"])
            self.assertNotIn("emotions.json", summary["artifacts"])

    def test_optional_llm_failure_keeps_exported_primary_result(self):
        with tempfile.TemporaryDirectory() as temp:
            def optional_failure(arguments):
                plan = json.loads(Path(arguments[arguments.index("--plan") + 1]).read_text(encoding="utf-8"))
                steps = []
                for step in plan["steps"]:
                    failed = step["name"] == "llm-metadata"
                    steps.append({"name": step["name"], "status": "failed" if failed else "accepted", "accepted": not failed,
                                  "conservative_observed_peak_bytes": 1000})
                    if failed: break
                destination = Path(arguments[arguments.index("--output-dir") + 1]); destination.mkdir(parents=True)
                (destination / "results.json").write_text(json.dumps({"all_steps_accepted": False, "steps": steps}), encoding="utf-8")
                return 2
            with mock.patch("run_quality_pipeline.bounded.main", side_effect=optional_failure), \
                    mock.patch("run_quality_pipeline.subprocess.run") as adb, contextlib.redirect_stdout(io.StringIO()):
                result = main(["--serial", "fake", "--remote-root", "/models", "--input-wav", "synthetic.wav",
                    "--output-dir", temp, "--metadata-mode", "literal"])
            self.assertEqual(result, 0)
            summary = json.loads((Path(temp) / "pipeline.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "completed_with_optional_failures")
            self.assertIn("transcript.json", summary["artifacts"])
            self.assertNotIn("metadata.json", summary["artifacts"])

    def test_nemotron_uses_fixed_native_preset_and_rttm_dataflow(self):
        plan, paths, _ = build_plan(self.args("--diarization-engine", "nemotron"), "nemo")
        command, target = self.command(next(s for s in plan["steps"] if s["name"] == "diarization"))
        self.assertEqual(command[:2], ["env", "LD_LIBRARY_PATH=pipeline2/nemotron/lib:lib:llama/lib"])
        self.assertEqual(command[2:5], ["/data/local/tmp/models/pipeline2/nemotron/bin/diarize_file",
            "/data/local/tmp/models/pipeline2/nemotron/model.gguf", "/data/local/tmp/models/fixture.wav"])
        self.assertEqual(command[5:], ["--gpu", "-1", "--preset", "v3-offline", "--rttm", "recording"])
        self.assertNotIn("--offline", command)
        self.assertEqual(target, paths["diar.log"])
        export = next(s for s in plan["steps"] if s["name"] == "export-transcript")["argv"]
        self.assertIn(paths["diar.log"], export)

    def test_nemotron_model_and_binary_overrides_are_literal(self):
        plan, _, _ = build_plan(self.args("--diarization-engine", "nemotron", "--nemotron-model", "custom ' model.gguf",
            "--nemotron-bin", "/custom/nemo tool"), "custom")
        command, _ = self.command(next(s for s in plan["steps"] if s["name"] == "diarization"))
        self.assertEqual(command[2], "/custom/nemo tool")
        self.assertEqual(command[3], "/data/local/tmp/models/custom ' model.gguf")
        self.assertEqual(self.args().diarization_engine, "pyannote")

    def test_llm_memory_configuration_is_bounded(self):
        plan, _, _ = build_plan(self.args(), "budget")
        command, _ = self.command(next(s for s in plan["steps"] if s["name"] == "llm-metadata"))
        for option, value in (("-c", "1024"), ("-b", "128"), ("-ub", "64"), ("-t", "2"),
                              ("--threads-batch", "2"), ("-n", "96"), ("--reasoning", "off"), ("-ngl", "0")):
            self.assertEqual(command[command.index(option) + 1], value)
        self.assertIn("--no-repack", command)
        self.assertIn("--json-schema-file", command)
        self.assertIn("--chat-template-file", command)
        self.assertEqual(self.args().budget_bytes, 2_000_000_000)
        self.assertEqual(self.args().reserve_bytes, 128 * 1024 * 1024)

    def test_candidate_metadata_is_selection_only_and_uses_fresh_native_pool(self):
        plan, paths, job = build_plan(self.args("--metadata-mode", "candidates"), "selection")
        self.assertEqual(paths["candidate-pool.json"], job + "/candidate-pool.json")
        prompt = next(s for s in plan["steps"] if s["name"] == "build-prompt")["argv"]
        self.assertEqual(prompt[-5:], ["prompt-candidates", paths["asr.log"],
            "/data/local/tmp/models/pipeline2/llm/candidate-keywords.prompt.txt", paths["prompt.txt"], paths["candidate-pool.json"]])
        metadata = next(s for s in plan["steps"] if s["name"] == "validate-metadata")["argv"]
        self.assertEqual(metadata[-6:], ["metadata-candidates", paths["asr.log"], paths["candidate-pool.json"],
            paths["llm.log"], paths["metadata.json"], paths["metadata.audit.json"]])
        llm, _ = self.command(next(s for s in plan["steps"] if s["name"] == "llm-metadata"))
        for option, value in (("--temp", "0"), ("-n", "64"), ("-f", paths["prompt.txt"])):
            self.assertEqual(llm[llm.index(option) + 1], value)
        self.assertTrue(llm[llm.index("--json-schema-file") + 1].endswith("candidate-keywords.schema.json"))
        self.assertNotIn("metadata-user.txt", " ".join(llm))
        self.assertLess([s["name"] for s in plan["steps"]].index("giga-asr"), [s["name"] for s in plan["steps"]].index("build-prompt"))

    def test_candidate_template_and_schema_paths_are_literal_overrides(self):
        plan, paths, _ = build_plan(self.args("--metadata-mode", "candidates", "--prompt-template", "custom ' template.txt",
            "--metadata-schema", "custom schema.json"), "overrides")
        prompt = next(s for s in plan["steps"] if s["name"] == "build-prompt")["argv"]
        self.assertEqual(prompt[-3], "/data/local/tmp/models/custom ' template.txt")
        llm, _ = self.command(next(s for s in plan["steps"] if s["name"] == "llm-metadata"))
        self.assertEqual(llm[llm.index("--json-schema-file") + 1], "/data/local/tmp/models/custom schema.json")
        self.assertEqual(self.args().metadata_mode, "literal")
        self.assertEqual(len(paths), 12)

    def test_candidate_schema_and_prompt_only_request_selection_ids(self):
        template_dir = Path(__file__).resolve().parents[1] / "templates"
        schema = json.loads((template_dir / "candidate-keywords.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(set(schema["properties"]), {"candidate_ids"})
        self.assertFalse(schema["additionalProperties"])
        ids = schema["properties"]["candidate_ids"]
        self.assertEqual(ids["maxItems"], 3)
        self.assertTrue(ids["uniqueItems"])
        self.assertEqual(ids["items"], {"type": "integer", "minimum": 1, "maximum": 24})
        prompt = (template_dir / "candidate-keywords.prompt.txt").read_text(encoding="utf-8")
        self.assertEqual(prompt.count("{{RAW_TRANSCRIPT}}"), 1)
        self.assertEqual(prompt.count("{{CANDIDATES_JSON}}"), 1)
        self.assertIn('"candidate_ids"', prompt)

    def test_optional_whisper_does_not_replace_primary_asr(self):
        plan, paths, job = build_plan(self.args("--whisper-model", "comparison.bin"), "second-pass")
        self.assertEqual(len(plan["steps"]), 9)
        command, _ = self.command(next(s for s in plan["steps"] if s["name"] == "whisper-comparison"))
        self.assertIn("-nfa", command)
        self.assertNotIn("-fa", command)
        for option in ("-bs", "-bo", "-t"):
            self.assertEqual(command[command.index(option) + 1], "2")
        self.assertEqual(command[command.index("-p") + 1], "1")
        self.assertIn("-ojf", command)
        self.assertIn("-otxt", command)
        self.assertEqual(command[command.index("-of") + 1], job + "/whisper")
        export = next(s for s in plan["steps"] if s["name"] == "export-transcript")["argv"]
        self.assertIn(paths["asr.log"], export)
        self.assertNotIn(paths["whisper.json"], export)

    def test_whisper_quality_parameters_are_explicit_and_overridable(self):
        plan, _, _ = build_plan(self.args("--whisper-model", "large-q5.bin", "--whisper-threads", "4",
            "--whisper-beam-size", "3", "--whisper-best-of", "4", "--whisper-flash-attn"), "quality")
        command, _ = self.command(next(s for s in plan["steps"] if s["name"] == "whisper-comparison"))
        for option, value in (("-t", "4"), ("-bs", "3"), ("-bo", "4"), ("-p", "1")):
            self.assertEqual(command[command.index(option) + 1], value)
        self.assertIn("-fa", command)
        self.assertNotIn("-nfa", command)

    def test_invalid_whisper_parameters_are_rejected(self):
        for option in ("--whisper-beam-size", "--whisper-best-of", "--whisper-threads"):
            for value in ("0", "-1"):
                with self.subTest(option=option, value=value), self.assertRaises(ValueError):
                    build_plan(self.args("--whisper-model", "comparison.bin", option, value), "invalid")

    def test_invalid_configuration_and_sessions_rejected(self):
        for extra in (("--remote-root", "/"), ("--remote-root", "relative"), ("--remote-root", "/data/../tmp"),
                      ("--reserve-bytes", "2000000000"), ("--sample-ms", "99"), ("--audio-seconds", "nan"),
                      ("--input-wav", "")):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                build_plan(self.args(*extra), "valid")
        for session in ("../escape", "bad space", ""):
            with self.subTest(session=session), self.assertRaises(ValueError):
                build_plan(self.args(), session)

    def test_failed_or_partial_plan_cannot_enable_retrieval(self):
        report = {"all_steps_accepted": True, "steps": [{"accepted": True, "status": "accepted"}]}
        self.assertTrue(fully_accepted(report, 1))
        self.assertFalse(fully_accepted(report, 2))
        for status in ("failed", "memory_rejected", "running"):
            rejected = copy.deepcopy(report)
            rejected["steps"][0]["status"] = status
            self.assertFalse(fully_accepted(rejected, 1))
        self.assertFalse(fully_accepted({"steps": []}, 0))

    def test_orchestrator_never_pulls_after_memory_rejection(self):
        with tempfile.TemporaryDirectory() as temp:
            def rejected(arguments):
                destination = Path(arguments[arguments.index("--output-dir") + 1])
                destination.mkdir(parents=True)
                (destination / "results.json").write_text(json.dumps({"all_steps_accepted": False, "steps": []}), encoding="utf-8")
                return 2
            with mock.patch("run_quality_pipeline.bounded.main", side_effect=rejected), \
                    mock.patch("run_quality_pipeline.subprocess.run") as adb, contextlib.redirect_stdout(io.StringIO()):
                result = main(["--serial", "fake", "--remote-root", "/data/local/tmp/models",
                    "--input-wav", "synthetic.wav", "--output-dir", temp])
            self.assertEqual(result, 2)
            self.assertEqual(len(adb.call_args_list), 1)  # setup mkdir only
            self.assertNotIn("pull", adb.call_args_list[0].args[0])
            self.assertFalse(json.loads((Path(temp) / "pipeline.json").read_text(encoding="utf-8"))["custom_artifacts_retrieved"])

    def test_orchestrator_pulls_only_after_complete_accepted_plan(self):
        with tempfile.TemporaryDirectory() as temp:
            def accepted(arguments):
                plan = json.loads(Path(arguments[arguments.index("--plan") + 1]).read_text(encoding="utf-8"))
                destination = Path(arguments[arguments.index("--output-dir") + 1])
                destination.mkdir(parents=True)
                steps = [{"name": s["name"], "accepted": True, "status": "accepted"} for s in plan["steps"]]
                (destination / "results.json").write_text(json.dumps({"all_steps_accepted": True, "steps": steps,
                    "accepted_pipeline_peak_bytes": 1234}), encoding="utf-8")
                return 0
            with mock.patch("run_quality_pipeline.bounded.main", side_effect=accepted), \
                    mock.patch("run_quality_pipeline.subprocess.run") as adb, contextlib.redirect_stdout(io.StringIO()):
                result = main(["--serial", "fake", "--remote-root", "/data/local/tmp/models",
                    "--input-wav", "synthetic.wav", "--output-dir", temp])
            self.assertEqual(result, 0)
            self.assertEqual(sum("pull" in call.args[0] for call in adb.call_args_list), 7)
            summary = json.loads((Path(temp) / "pipeline.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "completed")
            self.assertTrue(summary["custom_artifacts_retrieved"])


if __name__ == "__main__":
    unittest.main()
