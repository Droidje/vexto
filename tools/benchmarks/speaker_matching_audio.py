"""Extend the existing speaker-identity research to four real AMI meetings/person.

Reuses the explicitly supplied research checkout's pinned encoders and AMI reader.
Audio, model weights and embeddings belong in ignored .agents/work, never Git.
This is CPU inference on annotated headset speech, not an Android end-to-end test.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import importlib.metadata
import json
from pathlib import Path
import shutil
import re
import subprocess
import tempfile
import time
import wave
import zipfile


def module(root, name):
    spec = importlib.util.spec_from_file_location(name, root / f"{name}.py")
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def ranged(url, start, end):
    with tempfile.TemporaryDirectory() as folder:
        headers, data = Path(folder) / "headers", Path(folder) / "data"
        subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--retry", "2",
                        "--max-time", "90", "--range", f"{start}-{end}", "--dump-header", str(headers),
                        "--output", str(data), url], check=True)
        ranges = re.findall(r"content-range:\s*bytes (\d+)-(\d+)/(\d+)", headers.read_text(), re.I)
        if not ranges or tuple(map(int, ranges[-1][:2])) != (start, end):
            raise ValueError("Server did not return the requested byte range")
        payload = data.read_bytes()
        if len(payload) != end - start + 1:
            raise ValueError("Truncated range response")
        return payload, int(ranges[-1][2])


def compact_clip(ami, url, path, spans):
    header, size = ranged(url, 0, 65535)
    offset, channels, rate, bits, data_size = ami.wav_layout(header)
    frame = channels * (bits // 8)
    ranges = [(offset + round(a * rate) * frame, offset + round(b * rate) * frame - 1) for a, b in spans]
    if not ranges or any(a < offset or b >= min(size, offset + data_size) for a, b in ranges):
        raise ValueError("Invalid source ranges")
    # One bounded request avoids many tiny requests to the public corpus server.
    first, last = min(a for a, _ in ranges), max(b for _, b in ranges)
    if last - first > 4 * 1024 * 1024:
        return ami.make_clip(url, path, spans)
    blob, _ = ranged(url, first, last)
    pcm, segments, provenance, elapsed = bytearray(), [], [], 0.
    for a, b in ranges:
        part = blob[a - first:b - first + 1]
        duration = len(part) / (rate * frame)
        segments.append([round(elapsed, 3), round(elapsed + duration, 3)])
        elapsed += duration
        pcm.extend(part)
        provenance.append(dict(url=url, byte_start=a, byte_end=b, sha256=hashlib.sha256(part).hexdigest()))
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as target:
        target.setnchannels(channels); target.setsampwidth(bits // 8); target.setframerate(rate)
        target.writeframes(pcm)
    return segments, provenance


def prepare(root, out):
    ami = module(root, "prepare_ami")
    ami.ranged = ranged
    ami.MEETINGS = tuple(f"ES{group}{suffix}" for group in (2002, 2004, 2003, 2005) for suffix in "abcd")
    annotations = root / "data/ami_public_manual_1.6.2.zip"
    if not annotations.is_file():
        raise ValueError("Prepare the existing research annotations first")
    previous = json.loads((root / "data/ami-v2/manifest.json").read_text())["items"]
    previous = {(item["session_id"], item["local_id"]): item for item in previous}
    previous_sources = json.loads((root / "data/ami-v2/sources.json").read_text())
    previous_sources = {item["url"]: item for item in previous_sources if "source_ranges" in item}
    tasks = []
    with zipfile.ZipFile(annotations) as archive:
        people = ami.read_speakers(archive)
        for group in (2002, 2003, 2004, 2005):
            sets = [{i["person_id"] for i in people[f"ES{group}{s}"].values()} for s in "abcd"]
            if any(s != sets[0] for s in sets[1:]):
                raise ValueError("Participant identity changes across meetings")
        for meeting in ami.MEETINGS:
            for agent, person in people[meeting].items():
                tasks.append((meeting, agent, person, ami.clean_segments(archive, meeting, agent)))

    def clip(task):
        meeting, agent, person, spans = task
        path = out / "clips" / f"{meeting}.{agent}.wav"
        metadata = path.with_suffix(".json")
        if metadata.is_file() and path.is_file():
            cached = json.loads(metadata.read_text())
            if cached["source"]["sha256"] != sha(path):
                raise ValueError("Cached clip SHA mismatch")
            return cached
        url = f"{ami.MEDIA_BASE}/{meeting}/audio/{meeting}.Headset-{person['channel']}.wav"
        prior = previous.get((meeting, agent))
        if prior:
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / "data/ami-v2" / prior["audio"], path)
            segments, spans = prior["segments"], prior["source_segments"]
            source = previous_sources[url]
        else:
            segments, ranges = compact_clip(ami, url, path, spans)
            source = {"url": url, "sha256": sha(path), "source_ranges": ranges}
        item = dict(session_id=meeting, local_id=agent, person_id=person["person_id"],
                    split="calibration" if meeting.startswith(("ES2002", "ES2004")) else "test",
                    audio=str(path.relative_to(out)), segments=segments, source_segments=spans)
        result = {"item": item, "source": source}
        save(metadata, result)
        print(f"Prepared {meeting}/{agent}", flush=True)
        return result

    with ThreadPoolExecutor(max_workers=2) as pool:
        clips = list(pool.map(clip, tasks))
    write_dataset(root, out, annotations, clips, 0)


def write_dataset(root, out, annotations, clips, excluded):
    save(out / "manifest.json", {"items": [c["item"] for c in clips], "excluded_incomplete_people": excluded})
    save(out / "sources.json", {"annotations_sha256": sha(annotations),
                               "helper_sha256": sha(root / "prepare_ami.py"),
                               "clips": [c["source"] for c in clips]})
    print(f"Prepared {len(clips)} annotated speaker/session profiles", flush=True)


def complete_cached(root, out):
    """Availability-only exclusion, before inference: drop an entire incomplete person."""
    grouped = defaultdict(list)
    for path in sorted((out / "clips").glob("*.json")):
        value = json.loads(path.read_text())
        if value["source"]["sha256"] != sha(out / value["item"]["audio"]):
            raise ValueError("Cached clip SHA mismatch")
        grouped[value["item"]["person_id"]].append(value)
    complete = [rows for rows in grouped.values() if len({r["item"]["session_id"] for r in rows}) == 4 and len(rows) == 4]
    write_dataset(root, out, root / "data/ami_public_manual_1.6.2.zip",
                  sorted([r for rows in complete for r in rows], key=lambda r: (r["item"]["session_id"], r["item"]["local_id"])),
                  len(grouped) - len(complete))


def short_windows(segments):
    """VoiceprintProvider window selection, retaining source cut boundaries."""
    windows = []
    usable = [(round(a * 16000), round(b * 16000)) for a, b in segments if b - a >= .3]
    hop = max(8000, sum(b - a for a, b in usable) // 2500)
    for start, end in usable:
        if end - start <= 24000:
            windows.append((start, end))
        else:
            at = start
            while at + 16000 < end:
                windows.append((at, at + 16000))
                at += hop
            windows.append((end - 16000, end))
    if len(windows) > 32:
        windows = [windows[i * (len(windows) - 1) // 31] for i in range(32)]
    return windows


def agc(waveform):
    """Mirror PcmAudio's 10ms energies / 510ms RMS / gain 1..10 on the excerpt."""
    import numpy as np
    count = (len(waveform) + 159) // 160
    energies = np.array([np.mean(waveform[i * 160:(i + 1) * 160] ** 2) for i in range(count)])
    prefix = np.concatenate(([0.], np.cumsum(energies, dtype=np.float64)))
    idx = np.arange(count)
    a, b = np.maximum(0, idx - 25), np.minimum(count, idx + 26)
    gains = np.clip(.1 / np.sqrt((prefix[b] - prefix[a]) / (b - a) + 1e-10), 1, 10)
    pos = (np.arange(len(waveform)) - 80) / 160
    k = np.clip(pos.astype(int), 0, count - 1)
    t = np.clip(pos - k, 0, 1)
    curve = gains[k] + (gains[np.minimum(k + 1, count - 1)] - gains[k]) * t
    return np.clip(waveform * curve, -1, 1).astype(np.float32)


def extract(root, out, model, style):
    import numpy as np
    import soundfile as sf
    import torch
    import torchaudio
    encoders = module(root, "encoders")
    common = module(root, "extract")
    manifest = out / "manifest.json"
    data = json.loads(manifest.read_text())["items"]
    torch.set_num_threads(4)
    torch.manual_seed(0)
    started_load = time.perf_counter()
    encode, revision = encoders.load_encoder(model, None, root / "models", "cpu", 4)
    load_seconds = time.perf_counter() - started_load
    started = time.perf_counter()
    items = []
    for item in data:
        path = out / item["audio"]
        wav, sr = sf.read(path, dtype="float32")
        if wav.ndim != 1 or not np.isfinite(wav).all():
            raise ValueError("Expected finite mono PCM")
        signal = torch.from_numpy(wav)
        if sr != 16000:
            signal = torchaudio.functional.resample(signal, sr, 16000)
        if style == "short-agc":
            windows = short_windows(item["segments"])
            signal = torch.from_numpy(agc(signal.numpy()))
        else:
            budget = 10 if style == "long10" else 30
            windows = [(round(a * 16000), round(b * 16000)) for a, b in common.selected_segments(item["segments"], budget)]
        vectors, durations = [], []
        for a, b in windows:
            with torch.inference_mode():
                vector = encode(signal[a:b].unsqueeze(0)).flatten().cpu()
            if not torch.isfinite(vector).all() or vector.norm() <= 0:
                raise ValueError("Invalid model embedding")
            vectors.append(vector / vector.norm())
            durations.append((b - a) / 16000)
        pooled = sum(v * d for v, d in zip(vectors, durations))
        pooled = pooled / pooled.norm()
        # Unique source speech, not overlapping inference-window duration.
        speech = sum(b - a for a, b in item["segments"]) if style == "short-agc" else sum(durations)
        items.append({**{k: item[k] for k in ("session_id", "local_id", "person_id", "split")},
                      "embedding": pooled.tolist(), "speech_seconds": speech,
                      "audio_sha256": sha(path), "window_count": len(windows)})
        print(f"{model}/{style}: {len(items)}/{len(data)}", flush=True)
    save(out / f"{model}-{style}.json", {
        "model": model, "model_revision": revision, "style": style,
        "extractor_sha256": sha(Path(__file__)),
        "manifest_sha256": sha(manifest), "load_seconds": load_seconds,
        "extraction_seconds": time.perf_counter() - started,
        "helpers_sha256": {n: sha(root / f"{n}.py") for n in ("encoders", "extract")},
        "packages": {n: importlib.metadata.version(n) for n in
                     ("torch", "torchaudio", "speechbrain", "sherpa-onnx", "onnxruntime", "soundfile")},
        "items": items})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--research-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--complete-cached", action="store_true", help="use only people with all four verified cached meetings")
    parser.add_argument("--model", choices=("eres2net", "ecapa", "wespeaker"))
    parser.add_argument("--style", choices=("short-agc", "long10", "long30"), default="long10")
    args = parser.parse_args()
    if args.prepare:
        prepare(args.research_root, args.output)
    if args.complete_cached:
        complete_cached(args.research_root, args.output)
    if args.model:
        # extract.py imports encoders by module name when loaded from its research directory.
        import sys
        sys.path.insert(0, str(args.research_root.resolve()))
        extract(args.research_root, args.output, args.model, args.style)


if __name__ == "__main__":
    main()
