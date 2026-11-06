"""Run prepared native Android steps sequentially under a sampled memory guard.

Plan: {"steps": [{"name": "asr-1", "kind": "asr", "argv": ["bin/model",
"--argument=value"], "audio_seconds": 21.0}]}. Commands run in --remote-root.
Use argv ["env", "LD_LIBRARY_PATH=lib", ...] when libraries need a search path.

The budget covers the native process tree plus its guard, with an extra reserve.
It excludes Android itself. Sampling is not a cgroup-enforced hard memory limit.
Python 3.10+, ADB and Android toybox; no host-side model inference.
"""
import argparse
import datetime as dt
import hashlib
import json
import math
import re
import shlex
import subprocess
import time
import uuid
from pathlib import Path, PurePosixPath

DEFAULT_BUDGET = 2_000_000_000
DEFAULT_RESERVE = 128 * 1024 * 1024
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z")


def validate_budget(budget, reserve):
    if isinstance(budget, bool) or not isinstance(budget, int) or not 0 < budget <= 2**63 - 1:
        raise ValueError("budget-bytes must be a positive signed 64-bit integer")
    if isinstance(reserve, bool) or not isinstance(reserve, int) or not 0 <= reserve < budget:
        raise ValueError("reserve-bytes must be nonnegative and smaller than budget")


def validate_plan(plan):
    if not isinstance(plan, dict) or set(plan) != {"steps"}:
        raise ValueError("plan must contain only a steps array")
    if not isinstance(plan["steps"], list) or not plan["steps"]:
        raise ValueError("steps must be a nonempty array")
    names = set()
    for step in plan["steps"]:
        if not isinstance(step, dict) or set(step) - {"name", "kind", "argv", "audio_seconds"}:
            raise ValueError("step contains unsupported fields")
        if not isinstance(step.get("name"), str) or not NAME.fullmatch(step["name"]):
            raise ValueError("step name must be a short unique filename-safe identifier")
        if step["name"] in names:
            raise ValueError("step names must be unique")
        names.add(step["name"])
        if not isinstance(step.get("kind"), str) or not NAME.fullmatch(step["kind"]):
            raise ValueError("step kind must be a short identifier")
        argv = step.get("argv")
        if not isinstance(argv, list) or not argv or not all(isinstance(x, str) and "\0" not in x for x in argv):
            raise ValueError("argv must be a nonempty array of strings without NUL")
        if not argv[0]:
            raise ValueError("argv executable must not be empty")
        if "audio_seconds" in step:
            duration = step["audio_seconds"]
            if isinstance(duration, bool) or not isinstance(duration, (int, float)) or not math.isfinite(duration) or duration <= 0:
                raise ValueError("audio_seconds must be a finite positive number")
    return plan["steps"]


def parse_time_metrics(stderr):
    """Read the final outer toybox time report, not a model's inner report."""
    peaks = re.findall(r"Max RSS \(KiB\):\s*(\d+)", stderr)
    walls = re.findall(r"Real time \(s\):\s*([0-9]+(?:\.[0-9]+)?)", stderr)
    if not peaks or not walls:
        raise ValueError("missing outer toybox time wall/RSS metrics")
    return {"native_peak_rss_kib": int(peaks[-1]), "native_wall_seconds": float(walls[-1])}


def parse_guard_metrics(text):
    values = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        key, sep, value = line.partition("=")
        if not sep or not re.fullmatch(r"[a-z_]+", key) or not re.fullmatch(r"\d+", value):
            raise ValueError("invalid guard metrics line")
        if key in values:
            raise ValueError("duplicate guard metric")
        values[key] = int(value)
    required = {"model_exit_code", "memory_rejected", "timed_out", "samples",
                "sampled_peak_tree_rss_kib", "sampled_peak_tree_pss_kib",
                "peak_tree_sum_vmhwm_kib", "supervisor_peak_vmhwm_kib",
                "pss_complete_samples", "remaining_owned_processes"}
    if not required <= values.keys() or values["samples"] < 1:
        raise ValueError("incomplete guard metrics")
    if values["memory_rejected"] not in (0, 1) or values["timed_out"] not in (0, 1):
        raise ValueError("invalid guard flags")
    return values


def assess_step(guard, native, budget, reserve):
    validate_budget(budget, reserve)
    # Sum of per-process high-water marks is deliberately conservative. Shared
    # pages may be counted more than once; this is not simultaneous exact PSS.
    peak = max(guard["sampled_peak_tree_rss_kib"],
               guard["peak_tree_sum_vmhwm_kib"],
               native["native_peak_rss_kib"] + guard["supervisor_peak_vmhwm_kib"]) * 1024
    within = peak + reserve <= budget
    if guard["memory_rejected"] or not within:
        status = "memory_rejected"
    elif guard["model_exit_code"] or guard["timed_out"] or guard["remaining_owned_processes"]:
        status = "failed"
    else:
        status = "accepted"
    return {"status": status, "accepted": status == "accepted",
            "conservative_observed_peak_bytes": peak,
            "observed_peak_plus_reserve_bytes": peak + reserve,
            "budget_check_passed": within,
            "native_peak_plus_reserve_bytes": native["native_peak_rss_kib"] * 1024 + reserve,
            "native_budget_check_passed": native["native_peak_rss_kib"] * 1024 + reserve <= budget}


def save_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--remote-root", required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--budget-bytes", type=int, default=DEFAULT_BUDGET)
    parser.add_argument("--reserve-bytes", type=int, default=DEFAULT_RESERVE)
    parser.add_argument("--sample-ms", type=int, default=150, metavar="100..200")
    parser.add_argument("--step-timeout-seconds", type=int, default=900)
    parser.add_argument("--stop-on-error", action="store_true")
    args = parser.parse_args(argv)
    try:
        validate_budget(args.budget_bytes, args.reserve_bytes)
        steps = validate_plan(json.loads(args.plan.read_text(encoding="utf-8-sig")))
        root = PurePosixPath(args.remote_root)
        if not root.is_absolute() or ".." in root.parts or root == PurePosixPath("/") or "\0" in args.remote_root:
            raise ValueError("remote-root must be an absolute non-root Android directory")
        if not 100 <= args.sample_ms <= 200:
            raise ValueError("sample-ms must be between 100 and 200")
        if args.step_timeout_seconds <= 0:
            raise ValueError("step-timeout-seconds must be positive")
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    guard_file = Path(__file__).with_name("android_memory_guard.sh")
    if not guard_file.is_file():
        parser.error("android_memory_guard.sh must be beside this script")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report_file = args.output_dir / "results.json"
    if report_file.exists():
        parser.error("output-dir already contains results.json; choose a fresh directory")
    adb = [args.adb, "-s", args.serial]

    def invoke(arguments, timeout=60):
        return subprocess.run(adb + arguments, capture_output=True, timeout=timeout)

    def shell(arguments, timeout=60):
        return invoke(["shell", shlex.join(arguments)], timeout)

    run_id = "bounded-" + uuid.uuid4().hex[:12]
    remote_dir = str(root / run_id)
    remote_guard = remote_dir + "/android_memory_guard.sh"
    environment = {}
    for key, command in {"boot_completed": ["getprop", "sys.boot_completed"],
                         "android_api": ["getprop", "ro.build.version.sdk"],
                         "abi_list": ["getprop", "ro.product.cpu.abilist"],
                         "meminfo_before": ["head", "-n", "5", "/proc/meminfo"]}.items():
        result = shell(command)
        result.check_returncode()
        environment[key] = result.stdout.decode("utf-8", "replace").strip()
    if environment["boot_completed"] != "1":
        raise RuntimeError("Android has not completed booting")
    shell(["mkdir", "-p", remote_dir]).check_returncode()
    invoke(["push", str(guard_file), remote_guard]).check_returncode()
    report = {"schema_version": 1, "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
              "status": "running", "run_id": run_id, "serial": args.serial,
              "remote_root": str(root), "environment": environment,
              "budget_bytes": args.budget_bytes, "reserve_bytes": args.reserve_bytes,
              "sampling_interval_ms": args.sample_ms,
              "measurement_scope": "native model process tree plus shell guard; Android OS excluded",
              "hard_limit_guarantee": False,
              "limitation": "RSS/PSS sampling can miss transients; toybox MaxRSS checks the native child high-water mark. Not a cgroup limit or an APK memory measurement.",
              "plan_sha256": hashlib.sha256(args.plan.read_bytes()).hexdigest(), "steps": []}
    save_json(report_file, report)
    for step in steps:
        item = dict(step, status="running", accepted=False)
        report["steps"].append(item)
        save_json(report_file, report)
        prefix = remote_dir + "/" + step["name"]
        command = ["sh", remote_guard, str(root), prefix, str(args.budget_bytes),
                   str(args.reserve_bytes), str(args.sample_ms),
                   str(args.step_timeout_seconds), "--"] + step["argv"]
        started = time.monotonic()
        try:
            invocation = shell(command, timeout=args.step_timeout_seconds + 45)
            item["adb_exit_code"] = invocation.returncode
            item["host_wall_seconds"] = time.monotonic() - started
            for stream in ("stdout", "stderr"):
                (args.output_dir / f'{step["name"]}.guard-{stream}.log').write_bytes(getattr(invocation, stream))
            for suffix in ("stdout.log", "stderr.log", "samples.tsv", "guard.txt"):
                target = args.output_dir / f'{step["name"]}.{suffix}'
                invoke(["pull", f"{prefix}.{suffix}", str(target)]).check_returncode()
            stdout = (args.output_dir / f'{step["name"]}.stdout.log').read_bytes()
            stderr = (args.output_dir / f'{step["name"]}.stderr.log').read_bytes()
            item["stdout_sha256"] = hashlib.sha256(stdout).hexdigest()
            item["stderr_sha256"] = hashlib.sha256(stderr).hexdigest()
            guard = parse_guard_metrics((args.output_dir / f'{step["name"]}.guard.txt').read_text(encoding="ascii"))
            item["guard"] = guard
            native = parse_time_metrics(stderr.decode("utf-8", "replace"))
            item.update(guard=guard, **native, **assess_step(guard, native, args.budget_bytes, args.reserve_bytes))
            if invocation.returncode and item["accepted"]:
                item.update(status="failed", accepted=False, error="guard transport exited unsuccessfully")
            if step.get("audio_seconds"):
                item["process_rtf"] = native["native_wall_seconds"] / step["audio_seconds"]
        except (OSError, ValueError, subprocess.SubprocessError) as exc:
            status = "memory_rejected" if item.get("guard", {}).get("memory_rejected") else "failed"
            item.update(status=status, accepted=False, error=str(exc))
        save_json(args.output_dir / f'{step["name"]}.json', item)
        save_json(report_file, report)
        print(json.dumps({key: item.get(key) for key in ("name", "status", "native_wall_seconds", "conservative_observed_peak_bytes")}), flush=True)
        # A transport failure must not start a second model while the first
        # may still be running. Only a completed guard can confirm cleanup.
        cleanup_confirmed = item.get("guard", {}).get("remaining_owned_processes") == 0
        if not cleanup_confirmed or (args.stop_on_error and not item["accepted"]):
            break
    report["all_steps_accepted"] = len(report["steps"]) == len(steps) and all(x["accepted"] for x in report["steps"])
    report["accepted_steps"] = [x["name"] for x in report["steps"] if x["accepted"]]
    report["max_accepted_step_peak_bytes"] = max((x["conservative_observed_peak_bytes"] for x in report["steps"] if x["accepted"]), default=None)
    report["accepted_pipeline_peak_bytes"] = report["max_accepted_step_peak_bytes"] if report["all_steps_accepted"] else None
    report["status"] = "completed" if report["all_steps_accepted"] else "completed_with_failures"
    report["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
    save_json(report_file, report)
    return 0 if report["all_steps_accepted"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
