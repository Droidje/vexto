"""Build and run a sequential Android speech pipeline with a measured RAM budget.

All inference, prompt construction, merging and metadata filtering run on Android.
The host orchestrates ADB, writes the plan and retrieves private results. Models,
WAV input, postprocess DEX and templates must already exist on the device.
Optional whisper.cpp adds an independent comparison, not an automatic ASR fix.
GTCRN is enabled by default; diarization keeps the original WAV. Frame-count
restoration does not establish sample-accurate denoiser alignment. LLM keyword
modes are opt-in; optional comparison/punctuation review is a separate diagnostic.
Optional GigaEmo uses original-wave speaker intervals after the core export;
failure never removes the completed RAW transcript.
"""
import argparse
import datetime as dt
import json
import shlex
import subprocess
import sys
import uuid
from pathlib import Path, PurePosixPath

import bounded_android_pipeline as bounded


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--remote-root", required=True)
    parser.add_argument("--input-wav", required=True, help="WAV already on Android; relative to remote-root or absolute")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--giga-dir", default="pipeline2/gigaam-v3/e2e-rnnt")
    denoise = parser.add_mutually_exclusive_group()
    denoise.add_argument("--denoise", dest="denoise", action="store_true")
    denoise.add_argument("--no-denoise", dest="denoise", action="store_false")
    parser.set_defaults(denoise=True)
    parser.add_argument("--denoiser-model", default="pipeline2/gtcrn/gtcrn_simple.onnx")
    parser.add_argument("--emo", action="store_true", help="Optional per-speaker emotion analysis on the original WAV")
    parser.add_argument("--emo-root", default="pipeline2/emo")
    parser.add_argument("--embedding", default="titanet-small.onnx")
    parser.add_argument("--segmentation", default="segmentation.int8.onnx")
    parser.add_argument("--diarization-engine", choices=("pyannote", "nemotron"), default="pyannote")
    parser.add_argument("--nemotron-model", default="pipeline2/nemotron/model.gguf")
    parser.add_argument("--nemotron-bin", default="pipeline2/nemotron/bin/diarize_file")
    parser.add_argument("--llm-model", default="pipeline2/llm/Qwen3-1.7B-Q4_K_M.gguf")
    parser.add_argument("--llm-bin", default="llama/bin/llama-completion")
    parser.add_argument("--postprocess-dex", default="pipeline2/postprocess.dex")
    parser.add_argument("--metadata-mode", choices=("none", "literal", "candidates"), default="none")
    parser.add_argument("--prompt-template", help="Optional template override; default depends on metadata-mode")
    parser.add_argument("--chat-template", default="pipeline2/llm/force-nonthinking.jinja")
    parser.add_argument("--metadata-schema", help="Optional schema override; default depends on metadata-mode")
    parser.add_argument("--whisper-model", help="Optional GGML model for an independent second pass")
    parser.add_argument("--whisper-bin", default="pipeline2/whisper-cpp/whisper-cli")
    parser.add_argument("--whisper-beam-size", type=int, default=2)
    parser.add_argument("--whisper-best-of", type=int, default=2)
    parser.add_argument("--whisper-threads", type=int, default=2)
    parser.add_argument("--whisper-flash-attn", action="store_true", help="Enable FA; default explicitly passes -nfa")
    parser.add_argument("--budget-bytes", type=int, default=bounded.DEFAULT_BUDGET)
    parser.add_argument("--reserve-bytes", type=int, default=bounded.DEFAULT_RESERVE)
    parser.add_argument("--sample-ms", type=int, default=150, metavar="100..200")
    parser.add_argument("--step-timeout-seconds", type=int, default=900)
    parser.add_argument("--audio-seconds", type=float, help="Optional known duration for reporting only")
    return parser


def remote_path(root, value):
    if not isinstance(value, str) or not value or "\0" in value:
        raise ValueError("remote paths must be nonempty strings without NUL")
    path = PurePosixPath(value)
    return str(path if path.is_absolute() else root / path)


def build_plan(args, session_id):
    """Pure construction: no ADB or file reads; synthetic fixtures can test it."""
    bounded.validate_budget(args.budget_bytes, args.reserve_bytes)
    root = PurePosixPath(args.remote_root)
    if not root.is_absolute() or root == PurePosixPath("/") or ".." in root.parts or "\0" in str(root):
        raise ValueError("remote-root must be an absolute non-root directory without ..")
    if not bounded.NAME.fullmatch(session_id):
        raise ValueError("session identifier must be filename-safe")
    if not 100 <= args.sample_ms <= 200 or args.step_timeout_seconds <= 0:
        raise ValueError("sample interval must be 100..200 ms and timeout positive")
    if min(args.whisper_beam_size, args.whisper_best_of, args.whisper_threads) < 1:
        raise ValueError("whisper beam-size, best-of and threads must be positive")
    if args.diarization_engine not in ("pyannote", "nemotron"):
        raise ValueError("unsupported diarization engine")
    if args.metadata_mode not in ("none", "literal", "candidates"):
        raise ValueError("unsupported metadata mode")
    job = str(root / ("quality-" + session_id))
    paths = {name: job + "/" + name for name in (
        "diar.log", "asr.log", "transcript.json", "transcript.provenance.json")}
    if args.metadata_mode != "none":
        paths.update({name: job + "/" + name for name in ("prompt.txt", "llm.log", "metadata.json", "metadata.audit.json")})
    candidates = args.metadata_mode == "candidates"
    prompt_template = args.prompt_template if args.prompt_template is not None else (
        "pipeline2/llm/candidate-keywords.prompt.txt" if candidates else "pipeline2/llm/metadata-prompt.template.txt")
    metadata_schema = args.metadata_schema if args.metadata_schema is not None else (
        "pipeline2/llm/candidate-keywords.schema.json" if candidates else "pipeline2/llm/metadata.schema.json")
    if candidates:
        paths["candidate-pool.json"] = job + "/candidate-pool.json"
    environment = ["env", "LD_LIBRARY_PATH=" + str(root / "lib") + ":" + str(root / "llama/lib")]
    source = remote_path(root, args.input_wav)
    giga = PurePosixPath(remote_path(root, args.giga_dir))
    steps = []

    def add(name, kind, argv, stdout=None):
        if stdout is not None:
            argv = ["sh", "-c", shlex.join(argv) + " > " + shlex.quote(stdout)]
        step = {"name": name, "kind": kind, "argv": argv}
        if args.audio_seconds is not None and kind in ("asr", "diarization", "asr-comparison"):
            step["audio_seconds"] = args.audio_seconds
        steps.append(step)

    java = ["env", "CLASSPATH=" + remote_path(root, args.postprocess_dex),
            "app_process", "/system/bin", "VextoPostprocess"]
    asr_source = source
    if args.denoise:
        paths.update({name: job + "/" + name for name in ("denoised.raw.wav", "denoised.wav", "denoise.audit.json")})
        add("denoise", "denoiser", environment + [str(root / "bin/sherpa-onnx-offline-denoiser"),
            "--speech-denoiser-gtcrn-model=" + remote_path(root, args.denoiser_model),
            "--input-wav=" + source, "--output-wav=" + paths["denoised.raw.wav"]])
        add("restore-denoised-length", "postprocess", java + ["align-denoised", source,
            paths["denoised.raw.wav"], paths["denoised.wav"], paths["denoise.audit.json"]])
        asr_source = paths["denoised.wav"]

    if args.diarization_engine == "nemotron":
        nemo_environment = ["env", "LD_LIBRARY_PATH=pipeline2/nemotron/lib:lib:llama/lib"]
        add("diarization", "diarization", nemo_environment + [remote_path(root, args.nemotron_bin),
            remote_path(root, args.nemotron_model), source, "--gpu", "-1", "--preset", "v3-offline",
            "--rttm", "recording"], paths["diar.log"])
    else:
        add("diarization", "diarization", environment + [str(root / "bin/sherpa-onnx-offline-speaker-diarization"),
            "--segmentation.pyannote-model=" + remote_path(root, args.segmentation),
            "--embedding.model=" + remote_path(root, args.embedding),
            "--segmentation.num-threads=2", "--embedding.num-threads=2",
            "--clustering.cluster-threshold=0.7", source], paths["diar.log"])
    add("giga-asr", "asr", environment + [str(root / "bin/sherpa-onnx-offline"),
        "--encoder=" + str(giga / "encoder.int8.onnx"), "--decoder=" + str(giga / "decoder.onnx"),
        "--joiner=" + str(giga / "joiner.onnx"), "--tokens=" + str(giga / "tokens.txt"),
        "--model-type=nemo_transducer", "--feat-dim=64", "--num-threads=2", asr_source], paths["asr.log"])
    if args.whisper_model is not None:
        binary = remote_path(root, args.whisper_bin)
        lib = str(PurePosixPath(binary).parent)
        whisper_env = ["env", environment[1] + ":" + lib + ":" + lib + "/lib"]
        paths.update({name: job + "/" + name for name in ("whisper.log", "whisper.json", "whisper.txt")})
        add("whisper-comparison", "asr-comparison", whisper_env + [binary,
            "-m", remote_path(root, args.whisper_model), "-f", asr_source, "-l", "ru",
            "-t", str(args.whisper_threads), "-p", "1", "-bs", str(args.whisper_beam_size),
            "-bo", str(args.whisper_best_of), "-ng", "-fa" if args.whisper_flash_attn else "-nfa",
            "-ojf", "-otxt", "-of", job + "/whisper"], paths["whisper.log"])
    add("export-transcript", "postprocess", java + ["export", paths["asr.log"], paths["diar.log"],
        paths["transcript.json"], paths["transcript.provenance.json"]])
    if args.emo:
        emo = PurePosixPath(remote_path(root, args.emo_root))
        paths["emotions.json"] = job + "/emotions.json"
        add("emotion-analysis", "emotion", ["env", "CLASSPATH=" + str(emo / "gigaemo.jar"),
            "LD_LIBRARY_PATH=" + str(emo), "app_process", "-Djava.library.path=" + str(emo), "/", "GigaEmo",
            str(emo / "emo.int8.onnx"), source, paths["emotions.json"], paths["diar.log"]])
    if args.metadata_mode == "none":
        plan = {"steps": steps}
        bounded.validate_plan(plan)
        return plan, paths, job
    prompt_arguments = ["prompt-candidates" if candidates else "prompt", paths["asr.log"],
        remote_path(root, prompt_template), paths["prompt.txt"]]
    if candidates:
        prompt_arguments.append(paths["candidate-pool.json"])
    add("build-prompt", "postprocess", java + prompt_arguments)
    llm = environment + [remote_path(root, args.llm_bin), "-m", remote_path(root, args.llm_model),
        "--jinja", "--chat-template-file", remote_path(root, args.chat_template), "--reasoning", "off",
        "-cnv", "--single-turn", "--simple-io", "--no-display-prompt", "-c", "1024", "-t", "2",
        "--threads-batch", "2", "-b", "128", "-ub", "64", "-ngl", "0", "--no-repack",
        "--cache-type-k", "f16", "--cache-type-v", "f16", "--seed", "42", "--temp", "0" if candidates else "0.7",
        "--top-k", "20", "--top-p", "0.8", "--min-p", "0", "--repeat-penalty", "1.0", "-n", "64" if candidates else "96",
        "--json-schema-file", remote_path(root, metadata_schema), "-f", paths["prompt.txt"]]
    add("llm-metadata", "llm", llm, paths["llm.log"])
    metadata_arguments = (["metadata-candidates", paths["asr.log"], paths["candidate-pool.json"], paths["llm.log"]]
        if candidates else ["metadata", paths["asr.log"], paths["llm.log"]])
    add("validate-metadata", "postprocess", java + metadata_arguments + [paths["metadata.json"], paths["metadata.audit.json"]])
    plan = {"steps": steps}
    bounded.validate_plan(plan)
    return plan, paths, job


def fully_accepted(report, expected_count):
    """Do not retrieve custom outputs after an incomplete or rejected plan."""
    steps = report.get("steps", [])
    return (report.get("all_steps_accepted") is True and len(steps) == expected_count
            and all(step.get("accepted") is True and step.get("status") == "accepted" for step in steps))


def main(argv=None):
    parser = argument_parser()
    args = parser.parse_args(argv)
    try:
        plan, outputs, job = build_plan(args, uuid.uuid4().hex)
    except ValueError as error:
        parser.error(str(error))
    if args.output_dir.exists() and (not args.output_dir.is_dir() or any(args.output_dir.iterdir())):
        parser.error("output-dir must be absent or empty; choose a fresh directory")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    summary_path = args.output_dir / "pipeline.json"
    if summary_path.exists() or (args.output_dir / "measurements/results.json").exists():
        parser.error("output-dir already contains a run; choose a fresh directory")
    plan_path = args.output_dir / "plan.json"
    bounded.save_json(plan_path, plan)
    summary = {"schema_version": 1, "status": "running", "remote_job_directory": job,
               "diarization_engine": args.diarization_engine,
               "metadata_mode": args.metadata_mode,
               "denoise": args.denoise,
               "emo": args.emo,
               "denoise_alignment_verified": False if args.denoise else None,
               "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
               "all_processing_location": "Android; host performs orchestration and artifact retrieval",
               "optional_whisper_role": "independent comparison; does not replace exported GigaAM text",
               "budget_bytes": args.budget_bytes, "reserve_bytes": args.reserve_bytes,
               "custom_artifacts_retrieved": False}
    bounded.save_json(summary_path, summary)
    adb = [args.adb, "-s", args.serial]
    try:
        subprocess.run(adb + ["shell", shlex.join(["mkdir", "-p", job])],
                       capture_output=True, check=True, timeout=60)
        result = bounded.main(["--adb", args.adb, "--serial", args.serial,
            "--remote-root", args.remote_root, "--plan", str(plan_path),
            "--output-dir", str(args.output_dir / "measurements"),
            "--budget-bytes", str(args.budget_bytes), "--reserve-bytes", str(args.reserve_bytes),
            "--sample-ms", str(args.sample_ms), "--step-timeout-seconds", str(args.step_timeout_seconds),
            "--stop-on-error"])
        measurements = json.loads((args.output_dir / "measurements/results.json").read_text(encoding="utf-8"))
        accepted = result == 0 and fully_accepted(measurements, len(plan["steps"]))
        summary["all_steps_accepted"] = accepted
        summary["accepted_pipeline_peak_bytes"] = measurements.get("accepted_pipeline_peak_bytes") if accepted else None
        statuses = {step["name"]: step for step in measurements.get("steps", [])}
        core_names = [step["name"] for step in plan["steps"][:next(i for i, step in enumerate(plan["steps"]) if step["name"] == "export-transcript") + 1]]
        core_accepted = all(statuses.get(name, {}).get("accepted") is True and statuses[name].get("status") == "accepted" for name in core_names)
        summary["core_steps_accepted"] = core_accepted
        summary["core_pipeline_peak_bytes"] = max((statuses[name].get("conservative_observed_peak_bytes", 0) for name in core_names), default=None) if core_accepted else None
        if not core_accepted:
            summary.update(status="failed", error="Plan failed or exceeded the observed memory budget; custom outputs not retrieved")
            return 2
        artifacts = args.output_dir / "artifacts"
        artifacts.mkdir(exist_ok=True)
        producers = {"diar.log": "diarization", "asr.log": "giga-asr", "transcript.json": "export-transcript",
            "transcript.provenance.json": "export-transcript", "denoised.raw.wav": "denoise", "denoised.wav": "restore-denoised-length",
            "denoise.audit.json": "restore-denoised-length", "prompt.txt": "build-prompt", "candidate-pool.json": "build-prompt",
            "emotions.json": "emotion-analysis",
            "llm.log": "llm-metadata", "metadata.json": "validate-metadata", "metadata.audit.json": "validate-metadata",
            "whisper.log": "whisper-comparison", "whisper.json": "whisper-comparison", "whisper.txt": "whisper-comparison"}
        retrieved = []
        for name, remote in outputs.items():
            producer = statuses.get(producers[name], {})
            if producer.get("accepted") is not True or producer.get("status") != "accepted":
                continue
            subprocess.run(adb + ["pull", remote, str(artifacts / name)], capture_output=True, check=True, timeout=60)
            retrieved.append(name)
        summary.update(status="completed" if accepted else "completed_with_optional_failures", custom_artifacts_retrieved=True, artifacts=retrieved)
        return 0
    except Exception as error:
        summary.update(status="failed", error=str(error))
        return 2
    finally:
        summary["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        bounded.save_json(summary_path, summary)
        print(json.dumps({"status": summary["status"], "custom_artifacts_retrieved": summary["custom_artifacts_retrieved"],
                          "remote_job_directory": job}), flush=True)


if __name__ == "__main__":
    sys.exit(main())
