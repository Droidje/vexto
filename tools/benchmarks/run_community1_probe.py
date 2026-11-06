"""Bounded local CPU probe of the official gated pyannote Community-1 snapshot.

No model mirror, remote inference, automatic gate acceptance, or token CLI option.
--check-access and --download do not import torch or process audio.
Run dependencies belong in an isolated directory passed as --extra-python-path.
Example: --work-dir CACHE --audio clip.wav --timeout-seconds 40
The timeout covers worker imports, model loading, inference and RTTM export.
Default timeout40seconds; explicit bounded retries may use up to60seconds.
Checkpoint batch32 is the default; --batch-size controls both public batch settings.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import wave

REPOSITORY = "pyannote/speaker-diarization-community-1"
REVISION = "3533c8cf8e369892e6b79ff1bf80f7b0286a54ee"
FILES = {
    "config.yaml": (444, "git:4022db43960736338378fdb6b5a85cfdae198910"),
    "segmentation/pytorch_model.bin": (5906507, "7ad24338d844fb95985486eb1a464e32d229f6d7a03c9abe60f978bacf3f816e"),
    "embedding/pytorch_model.bin": (26646242, "6f10ff60898a1d185fa22e1d11e0bfa8a92efec811f11bca48cb8cafebefd929"),
    "plda/plda.npz": (133852, "9b77bcd840692710dd3496f62ecfeed8d8e5f002fd991b785079b244eab7d255"),
    "plda/xvec_transform.npz": (134376, "325f1ce8e48f7e55e9c8aa47e05d2766b7c48c4b25b8de8dd751e7a4cc5fbe8f"),
}


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def file_hash(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def local_token(work: Path) -> str | None:
    token = os.environ.get("HF_TOKEN") or os.environ.get("HUGGING_FACE_HUB_TOKEN")
    if token:
        return token.strip()
    home = Path.home()
    hfhome = Path(os.environ.get("HF_HOME", str(Path(os.environ.get("XDG_CACHE_HOME", str(home / ".cache"))) / "huggingface")))
    for path in [Path(os.environ.get("HF_TOKEN_PATH", str(hfhome / "token"))),
                 work / "hf-home/token", home / ".huggingface/token"]:
        if path.is_file():
            return path.read_text(encoding="utf-8").strip() or None
    return None


def access_check(work: Path) -> dict:
    token = local_token(work)
    headers = {"User-Agent": "vexto-local-community1-probe"}
    if token:
        headers["Authorization"] = "Bearer " + token
    url = f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/config.yaml"
    report = {"repository": REPOSITORY, "revision": REVISION,
              "authenticated": bool(token), "conditions_accepted_by_probe": False}
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers, method="HEAD"), timeout=20) as response:
            report.update(http_status=response.status, access_granted=True)
    except urllib.error.HTTPError as error:
        report.update(http_status=error.code, access_granted=False, error_code=error.headers.get("X-Error-Code"))
    except (OSError, TimeoutError):
        report.update(http_status=None, access_granted=False, error_code="network_error")
    write_json(work / "access.json", report)
    return report


def verify_snapshot(snapshot: Path) -> list[dict]:
    artifacts = []
    for name, (size, expected) in FILES.items():
        path = snapshot / name
        if not path.is_file() or path.stat().st_size != size:
            raise ValueError(f"Missing/wrong-size official artifact: {name}")
        actual = file_hash(path)
        if expected.startswith("git:"):
            blob = hashlib.sha1(b"blob " + str(size).encode("ascii") + b"\0" + path.read_bytes()).hexdigest()
            valid = blob == expected[4:]
        else:
            valid = actual == expected
        if not valid:
            raise ValueError(f"Official artifact checksum mismatch: {name}")
        artifacts.append({"filename": name, "bytes": size, "sha256": actual,
                          "url": f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{name}"})
    return artifacts


def download(work: Path, snapshot: Path) -> dict:
    report = access_check(work)
    if not report["access_granted"]:
        return report
    # huggingface_hub strips credentials on cross-host redirects. No token is logged.
    from huggingface_hub import hf_hub_download
    token = local_token(work)
    for name in FILES:
        hf_hub_download(REPOSITORY, name, revision=REVISION, token=token,
                        local_dir=snapshot, cache_dir=work / "hf-cache")
    manifest = {"repository": REPOSITORY, "revision": REVISION, "license": "CC-BY-4.0",
                "artifacts": verify_snapshot(snapshot), "downloaded": True,
                "inference_performed": False, "conditions_accepted_by_probe": False}
    write_json(work / "model-manifest.json", manifest)
    return {"downloaded": True, "artifact_count": len(manifest["artifacts"]), "revision": REVISION}


class MemorySampler:
    """Single process RSS and OS high-water mark; no external dependencies."""
    def __init__(self, pid: int):
        self.pid, self.handle = pid, None
        if os.name == "nt":
            from ctypes import wintypes
            self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            self.kernel.OpenProcess.restype = wintypes.HANDLE
            self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
            self.kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
            self.handle = self.kernel.OpenProcess(0x0400 | 0x0010 | 0x0001, False, pid)
            if not self.handle:
                raise OSError("Cannot open inference process for RSS monitoring")
            class Counters(ctypes.Structure):
                _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
                    (name, ctypes.c_size_t) for name in ["PeakWorkingSetSize", "WorkingSetSize",
                    "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                    "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage", "PrivateUsage"]]
            self.Counters = Counters
            self.psapi = ctypes.WinDLL("psapi", use_last_error=True)
            self.psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]

    def sample(self) -> tuple[int, int]:
        if os.name == "nt":
            c = self.Counters(); c.cb = ctypes.sizeof(c)
            if self.psapi.GetProcessMemoryInfo(self.handle, ctypes.byref(c), c.cb):
                return int(c.WorkingSetSize), int(c.PeakWorkingSetSize)
            return 0, 0
        try:
            values = {}
            for line in Path(f"/proc/{self.pid}/status").read_text().splitlines():
                key, _, value = line.partition(":")
                if key in ("VmRSS", "VmHWM"):
                    values[key] = int(value.split()[0]) * 1024
            return values.get("VmRSS", 0), values.get("VmHWM", 0)
        except (OSError, ValueError):
            return 0, 0

    def close(self) -> None:
        if self.handle:
            self.kernel.CloseHandle(self.handle)

    def terminate(self) -> None:
        if self.handle:
            self.kernel.TerminateProcess(self.handle, 1)
        else:
            try:
                os.kill(self.pid, 9)
            except ProcessLookupError:
                pass


def process_parents() -> dict[int, int]:
    """PID->PPID without CIM, psutil or shell commands."""
    if os.name == "nt":
        from ctypes import wintypes
        class Entry(ctypes.Structure):
            _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
                        ("pid", wintypes.DWORD), ("heap", ctypes.c_size_t),
                        ("module", wintypes.DWORD), ("threads", wintypes.DWORD),
                        ("parent", wintypes.DWORD), ("priority", wintypes.LONG),
                        ("flags", wintypes.DWORD), ("exe", wintypes.WCHAR * 260)]
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        for name in ["Process32FirstW", "Process32NextW"]:
            getattr(kernel, name).argtypes = [wintypes.HANDLE, ctypes.POINTER(Entry)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.CreateToolhelp32Snapshot(2, 0)
        if handle == ctypes.c_void_p(-1).value:
            raise OSError("Cannot enumerate owned inference process tree")
        result = {}; entry = Entry(); entry.dwSize = ctypes.sizeof(entry)
        try:
            valid = kernel.Process32FirstW(handle, ctypes.byref(entry))
            while valid:
                result[int(entry.pid)] = int(entry.parent)
                valid = kernel.Process32NextW(handle, ctypes.byref(entry))
        finally:
            kernel.CloseHandle(handle)
        return result
    result = {}
    for path in Path("/proc").glob("[0-9]*/stat"):
        try:
            fields = path.read_text().rpartition(")")[2].split()
            result[int(path.parent.name)] = int(fields[1])
        except (OSError, ValueError, IndexError):
            continue
    return result


class ProcessTreeSampler:
    """Include Windows venv redirector children; retain OS handles for safe cleanup."""
    def __init__(self, pid: int):
        self.pid = pid
        self.samplers = {}
        self.high_water = {}
        self.active = set()

    def sample(self) -> tuple[int, int, int]:
        parents = process_parents(); owned = {self.pid}
        while True:
            expanded = owned | {pid for pid, parent in parents.items() if parent in owned}
            if expanded == owned:
                break
            owned = expanded
        self.active = owned & set(parents)
        rss_sum = 0
        for pid in self.active:
            if pid not in self.samplers:
                try:
                    self.samplers[pid] = MemorySampler(pid)
                except OSError:
                    continue  # Process may have exited between enumeration and OpenProcess.
            rss, peak = self.samplers[pid].sample()
            rss_sum += rss
            self.high_water[pid] = max(self.high_water.get(pid, 0), peak, rss)
        return rss_sum, max(self.high_water.values(), default=0), sum(self.high_water.values())

    def terminate(self, process: subprocess.Popen) -> None:
        self.sample()
        # Terminate descendants through their already-opened handles before the launcher.
        for pid in self.active - {self.pid}:
            if pid in self.samplers:
                self.samplers[pid].terminate()
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)

    def close(self) -> None:
        for sampler in self.samplers.values():
            sampler.close()


def worker(args: argparse.Namespace, snapshot: Path) -> None:
    from importlib.metadata import version
    import numpy as np
    import torch
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    from pyannote.audio import Pipeline
    with wave.open(str(args.audio), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getframerate() != 16000 or wav.getsampwidth() != 2:
            raise ValueError("Require mono PCM16 16kHz WAV; no implicit decoder/resampler")
        seconds = wav.getnframes() / 16000
        if not 0 < seconds <= 40:
            raise ValueError("Only short clips up to40seconds are permitted")
        waveform = torch.from_numpy(np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float32) / 32768).unsqueeze(0)
    started = time.perf_counter()
    pipeline = Pipeline.from_pretrained(str(snapshot), token=False)
    if pipeline is None:
        raise RuntimeError("Official local pipeline failed to load")
    # Public batch controls only: keep checkpoint, VBx and thresholds unchanged.
    pipeline.segmentation_batch_size = args.batch_size
    pipeline.embedding_batch_size = args.batch_size
    if pipeline.segmentation_batch_size != args.batch_size or pipeline.embedding_batch_size != args.batch_size:
        raise RuntimeError("Requested official pipeline batch controls were not applied")
    pipeline.to(torch.device("cpu"))
    loaded = time.perf_counter()
    with torch.inference_mode():
        output = pipeline({"waveform": waveform, "sample_rate": 16000})
    finished = time.perf_counter()
    regular, exclusive = output.speaker_diarization, output.exclusive_speaker_diarization
    for name, annotation in [("regular", regular), ("exclusive", exclusive)]:
        annotation.uri = "clip"
        with (args.work_dir / f"{name}.rttm").open("w", encoding="utf-8") as target:
            annotation.write_rttm(target)
    write_json(args.work_dir / "worker-result.json", {
        "status": "completed", "execution_location": "host_cpu", "torch": torch.__version__,
        "pyannote_audio": version("pyannote.audio"), "torchaudio": version("torchaudio"),
        "threads": 4, "interop_threads": 1, "audio_seconds": seconds,
        "worker_pid": os.getpid(),
        "segmentation_batch_size": pipeline.segmentation_batch_size,
        "embedding_batch_size": pipeline.embedding_batch_size,
        "model_load_wall_seconds": loaded - started, "inference_wall_seconds": finished - loaded,
        "regular_speaker_count": len(regular.labels()), "exclusive_speaker_count": len(exclusive.labels()),
        "regular_rttm_sha256": file_hash(args.work_dir / "regular.rttm"),
        "exclusive_rttm_sha256": file_hash(args.work_dir / "exclusive.rttm"),
        "forced_num_speakers": None, "not_android_measurement": True})


def run(args: argparse.Namespace, snapshot: Path) -> dict:
    artifacts = verify_snapshot(snapshot)
    result_path = args.work_dir / "worker-result.json"
    if result_path.exists():
        raise ValueError("Use a fresh work directory for each run to avoid stale results")
    command = [sys.executable, "-B", "-X", "utf8", str(Path(__file__).resolve()), "--worker",
               "--work-dir", str(args.work_dir), "--snapshot", str(snapshot), "--audio", str(args.audio.resolve()),
               "--batch-size", str(args.batch_size)]
    for path in args.extra_python_path:
        command += ["--extra-python-path", str(path.resolve())]
    env = os.environ.copy()
    env.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1",
               PYANNOTE_METRICS_ENABLED="0", OMP_NUM_THREADS="4", MKL_NUM_THREADS="4",
               OPENBLAS_NUM_THREADS="4", NUMEXPR_NUM_THREADS="4", CUDA_VISIBLE_DEVICES="")
    for key, folder in [("MPLCONFIGDIR", "mpl-config"), ("TORCH_HOME", "torch-home"),
                        ("TEMP", "tmp"), ("TMP", "tmp")]:
        location = args.work_dir / folder
        location.mkdir(exist_ok=True)
        env[key] = str(location)
    start = time.perf_counter(); sampled = peak = peak_sum = samples = 0; stop_reason = None
    with (args.work_dir / "worker.stdout").open("wb") as out, (args.work_dir / "worker.stderr").open("wb") as err:
        process = subprocess.Popen(command, stdout=out, stderr=err, env=env)
        sampler = None
        try:
            sampler = ProcessTreeSampler(process.pid)
            while process.poll() is None:
                rss, hwm, hwm_sum = sampler.sample(); samples += 1
                sampled, peak, peak_sum = max(sampled, rss), max(peak, hwm), max(peak_sum, hwm_sum)
                if time.perf_counter() - start >= args.timeout_seconds:
                    stop_reason = "timeout"
                elif args.memory_limit_bytes and max(sampled, peak_sum) > args.memory_limit_bytes:
                    stop_reason = "memory_limit"
                if stop_reason:
                    sampler.terminate(process); break
                time.sleep(0.1)
            rss, hwm, hwm_sum = sampler.sample()
            sampled, peak, peak_sum = max(sampled, rss), max(peak, hwm), max(peak_sum, hwm_sum)
        finally:
            if process.poll() is None:
                if sampler is not None:
                    sampler.terminate(process)
                else:
                    process.kill(); process.wait(timeout=5)
            if sampler is not None:
                sampler.close()
    report = {"repository": REPOSITORY, "revision": REVISION,
              "status": stop_reason or ("completed" if process.returncode == 0 else "failed"),
              "exit_code": process.returncode, "process_wall_seconds": time.perf_counter() - start,
              "sampled_peak_rss_bytes": sampled, "os_high_water_rss_bytes": peak,
              "sampled_tree_rss_peak_bytes": sampled,
              "largest_process_os_peak_rss_bytes": peak,
              "tree_sum_individual_peak_rss_bytes": peak_sum,
              "observed_peak_rss_bytes": max(sampled, peak_sum), "sampling_interval_ms": 100,
              "monitored_process_count": len(sampler.samplers),
              "samples": samples, "timeout_seconds": args.timeout_seconds,
              "memory_limit_bytes": args.memory_limit_bytes or None,
              "measurement_scope": "owned host process tree including venv launcher and interpreter; supervisor excluded; sum of per-process OS peaks is conservative and may overcount nonconcurrent peaks; not Android/APK",
              "hard_limit_guarantee": False, "threads": 4, "batch_size": args.batch_size,
              "artifacts": artifacts}
    if process.returncode == 0 and result_path.exists():
        report["worker"] = json.loads(result_path.read_text())
        report["memory_measurement_valid"] = report["worker"]["worker_pid"] in sampler.samplers
        if not report["memory_measurement_valid"]:
            report["status"] = "worker_not_observed"
    elif process.returncode == 0:
        report["status"] = "missing_worker_result"
    write_json(args.work_dir / "results.json", report)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--audio", type=Path)
    parser.add_argument("--extra-python-path", action="append", type=Path, default=[])
    parser.add_argument("--timeout-seconds", type=float, default=40)
    parser.add_argument("--memory-limit-bytes", type=int, default=0)
    parser.add_argument("--batch-size", type=int, default=32,
                        help="Official segmentation and embedding batch controls; checkpoint default32")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check-access", action="store_true")
    mode.add_argument("--download", action="store_true")
    mode.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not 0 < args.timeout_seconds <= 60 or args.memory_limit_bytes < 0:
        parser.error("timeout must be(0,60]seconds; memory limit must be nonnegative")
    if not 1 <= args.batch_size <= 32:
        parser.error("batch-size must be1..32")
    args.work_dir = args.work_dir.resolve(); args.work_dir.mkdir(parents=True, exist_ok=True)
    snapshot = (args.snapshot or args.work_dir / "snapshot").resolve()
    sys.path[:0] = [str(path.resolve()) for path in args.extra_python_path]
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["PYANNOTE_METRICS_ENABLED"] = "0"
    if args.check_access:
        print(json.dumps(access_check(args.work_dir), indent=2)); return
    if args.download:
        print(json.dumps(download(args.work_dir, snapshot), indent=2)); return
    if args.audio is None:
        parser.error("--audio is required for inference")
    if args.worker:
        worker(args, snapshot)
    else:
        report = run(args, snapshot)
        print(json.dumps({k:v for k,v in report.items() if k not in ["artifacts", "worker"]}, indent=2))
        if report["status"] != "completed":
            raise SystemExit(2)


if __name__ == "__main__":
    main()
