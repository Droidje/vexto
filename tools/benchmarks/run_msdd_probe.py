#!/usr/bin/env python3
"""Short, supervised NeMo MSDD *host CPU* reference probe (not Android).

Supply the official NeMo inference YAML and local .nemo checkpoints. Install
dependencies separately in an isolated environment, or pass --deps directories.
The reference RTTM in a fixture manifest is deliberately never fed to inference.
Outputs and logs are local research artifacts, and may contain private paths.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback
import wave


def save_json(path: Path, value: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def bounded_threads(value: str) -> int:
    result = int(value)
    if not 1 <= result <= 4:
        raise argparse.ArgumentTypeError("threads must be between 1 and 4")
    return result


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    sources = result.add_mutually_exclusive_group(required=True)
    sources.add_argument("--wav", type=Path)
    sources.add_argument("--manifest", type=Path, help="JSON fixtures array; references are not passed to the model")
    result.add_argument("--fixture-id", help="select one manifest entry")
    result.add_argument("--config", type=Path, required=True)
    result.add_argument("--msdd-model", type=Path, required=True)
    result.add_argument("--vad-model", type=Path, required=True)
    result.add_argument("--output-dir", type=Path, required=True)
    result.add_argument("--deps", type=Path, action="append", default=[])
    result.add_argument("--threads", type=bounded_threads, default=4)
    result.add_argument("--timeout-seconds", type=float, default=90)
    result.add_argument("--check-imports", action="store_true", help="import only; never load checkpoints or infer")
    result.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    return result


def fixtures(args: argparse.Namespace) -> list[dict]:
    if args.wav:
        return [{"id": args.wav.stem, "wav": str(args.wav.resolve())}]
    document = json.loads(args.manifest.read_text(encoding="utf-8"))
    rows = document["fixtures"]
    selected = [row for row in rows if not args.fixture_id or row["id"] == args.fixture_id]
    if not selected:
        raise ValueError("no selected fixtures")
    if len({row["id"] for row in selected}) != len(selected):
        raise ValueError("duplicate fixture ids")
    for row in selected:
        if Path(row["id"]).name != row["id"] or row["id"] in {".", ".."}:
            raise ValueError("fixture id must be a simple directory name")
    return selected


def configure_environment(args: argparse.Namespace) -> None:
    for key in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "NUMEXPR_NUM_THREADS"):
        os.environ[key] = str(args.threads)
    os.environ.update(CUDA_VISIBLE_DEVICES="", TOKENIZERS_PARALLELISM="false",
                      HF_HUB_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1")
    sys.path[:0] = [str(path.resolve()) for path in args.deps]


def worker(args: argparse.Namespace) -> int:
    configure_environment(args)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    result = {"location": "host CPU; not Android", "threads": args.threads, "status": "failed"}
    started = time.monotonic()
    try:
        import torch
        from omegaconf import OmegaConf
        from nemo.collections.asr.models.msdd_models import NeuralDiarizer

        torch.set_num_threads(args.threads)
        torch.set_num_interop_threads(1)
        result["import_seconds"] = time.monotonic() - started
        result["versions"] = {
            name: importlib.metadata.version(name)
            for name in ("nemo_toolkit", "torch", "torchaudio", "numpy", "lightning", "pyannote.metrics")
        }
        if args.check_imports:
            result["status"] = "imports_ok"
        else:
            wav = args.wav.resolve()
            with wave.open(str(wav), "rb") as audio:
                if audio.getnchannels() != 1 or audio.getframerate() != 16000 or audio.getsampwidth() != 2:
                    raise ValueError("probe input must be mono 16 kHz PCM16 WAV")
                duration = audio.getnframes() / audio.getframerate()
            if not 0 < duration <= 40:
                raise ValueError("use a short clip up to 40 seconds")
            result["audio_seconds"] = duration
            manifest = args.output_dir / "inference.jsonl"
            manifest.write_text(json.dumps({
                "audio_filepath": str(wav), "offset": 0, "duration": duration,
                "label": "infer", "text": "-", "num_speakers": None,
                "rttm_filepath": None, "uem_filepath": None,
            }) + "\n", encoding="utf-8")
            cfg = OmegaConf.load(args.config)
            cfg.device = "cpu"
            cfg.num_workers = 0
            cfg.batch_size = 16
            cfg.diarizer.manifest_filepath = str(manifest.resolve())
            cfg.diarizer.out_dir = str((args.output_dir / "diarization").resolve())
            cfg.diarizer.oracle_vad = False
            cfg.diarizer.vad.model_path = str(args.vad_model.resolve())
            cfg.diarizer.vad.external_vad_manifest = None
            cfg.diarizer.clustering.parameters.oracle_num_speakers = False
            cfg.diarizer.msdd_model.model_path = str(args.msdd_model.resolve())
            cfg.diarizer.msdd_model.parameters.use_speaker_model_from_ckpt = True
            cfg.diarizer.msdd_model.parameters.infer_batch_size = 16
            OmegaConf.save(cfg, args.output_dir / "effective-config.yaml")
            result["model_sha256"] = sha256(args.msdd_model)
            result["vad_sha256"] = sha256(args.vad_model)
            result["oracle_vad"] = result["oracle_num_speakers"] = False
            result["cascade"] = ["MarbleNet VAD", "five-scale checkpoint TitaNet", "NMESC clustering", "MSDD refinement"]
            checkpoint_started = time.monotonic()
            model = NeuralDiarizer(cfg=cfg)
            result["load_seconds"] = time.monotonic() - checkpoint_started
            infer_started = time.monotonic()
            model.eval()
            with torch.inference_mode():
                model.diarize()
            result["inference_seconds"] = time.monotonic() - infer_started
            predicted = list((args.output_dir / "diarization" / "pred_rttms").glob("*.rttm"))
            result["rttm_files"] = [str(path.relative_to(args.output_dir)) for path in predicted]
            if not predicted:
                raise RuntimeError("cascade completed without RTTM output")
            result["status"] = "completed"
    except Exception as exc:
        result["error_type"] = type(exc).__name__
        result["error"] = str(exc)
        traceback.print_exc()
    result["worker_wall_seconds"] = time.monotonic() - started
    save_json(args.output_dir / "worker-result.json", result)
    return 0 if result["status"] in {"completed", "imports_ok"} else 2


def supervise(args: argparse.Namespace, fixture: dict) -> dict:
    import psutil

    output = args.output_dir / fixture["id"]
    if output.exists() and any(output.iterdir()):
        raise FileExistsError(f"refusing nonempty fixture output directory: {output}")
    output.mkdir(parents=True, exist_ok=True)
    argv = [sys.executable, "-X", "utf8", str(Path(__file__).resolve()), "--worker",
            "--wav", str(Path(fixture["wav"]).resolve()), "--config", str(args.config.resolve()),
            "--msdd-model", str(args.msdd_model.resolve()), "--vad-model", str(args.vad_model.resolve()),
            "--output-dir", str(output.resolve()), "--threads", str(args.threads)]
    for dep in args.deps:
        argv += ["--deps", str(dep.resolve())]
    if args.check_imports:
        argv += ["--check-imports"]
    started = time.monotonic()
    peak_rss = peak_process_os_rss = 0
    timed_out = False
    with (output / "stdout.log").open("w", encoding="utf-8") as stdout, (output / "stderr.log").open("w", encoding="utf-8") as stderr:
        process = subprocess.Popen(argv, stdout=stdout, stderr=stderr)
        monitored = psutil.Process(process.pid)
        while process.poll() is None:
            try:
                family = [monitored] + monitored.children(recursive=True)
                rss = 0
                for item in family:
                    try:
                        memory = item.memory_info()
                        rss += memory.rss
                        peak_process_os_rss = max(peak_process_os_rss, getattr(memory, "peak_wset", memory.rss))
                    except psutil.Error:
                        pass
                peak_rss = max(peak_rss, rss)
            except psutil.Error:
                pass
            if time.monotonic() - started > args.timeout_seconds:
                timed_out = True
                for child in reversed(monitored.children(recursive=True)):
                    try:
                        child.kill()
                    except psutil.Error:
                        pass
                process.kill()
                break
            time.sleep(0.1)
        process.wait()
    report = {"fixture_id": fixture["id"], "location": "host CPU; not Android", "threads": args.threads,
              "status": "timeout" if timed_out else "failed", "returncode": process.returncode,
              "wall_seconds": time.monotonic() - started, "sampled_tree_rss_peak_bytes": peak_rss,
              "largest_process_os_peak_rss_bytes": peak_process_os_rss,
              "memory_note": "host process measurements, not an Android RAM/budget result; RSS sampled every 100 ms"}
    worker_result = output / "worker-result.json"
    if worker_result.exists():
        report["worker"] = json.loads(worker_result.read_text(encoding="utf-8"))
        if not timed_out and process.returncode == 0:
            report["status"] = report["worker"]["status"]
    save_json(output / "result.json", report)
    return report


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    if not 0 < args.timeout_seconds <= 90:
        raise ValueError("timeout must be positive and at most 90 seconds for this quick probe")
    if args.worker:
        return worker(args)
    configure_environment(args)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    reports = []
    for fixture in fixtures(args):
        report = supervise(args, fixture)
        reports.append(report)
        save_json(args.output_dir / "results.json", {"location": "host CPU; not Android", "runs": reports})
        print(json.dumps({key: report[key] for key in ("fixture_id", "status", "wall_seconds", "sampled_tree_rss_peak_bytes")}), flush=True)
        if report["status"] not in {"completed", "imports_ok"}:
            return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
