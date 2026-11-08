"""Verify that a standalone release APK contains the complete native runtime."""
import sys
import argparse
import hashlib
import json
from prepare_punctuation_models import ARTIFACTS
from prepare_quality_models import MANIFEST, ROOT
from zipfile import ZipFile


LIBRARIES = (
    "libvexto_nemotron_exec.so", "libvexto_whisper_exec.so", "libvexto_llama_exec.so",
    "libnemo_speech_asr.so", "libnemo_speech_asr_c.so",
    "libvexto_cpu.so", "libvexto_resampler.so", "libvexto_punct.so", "libvexto_emotion.so", "libvexto_emotion_jni.so", "libvexto_overlap_jni.so", "libonnxruntime.so",
    "libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so",
)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk")
parser.add_argument("--require-models", action="store_true", help="Verify every embedded FAST/QUALITY artifact in a standalone offline APK")
args = parser.parse_args()


def verify_asset(apk, name, size, checksum):
    if apk.getinfo(name).file_size != size:
        sys.exit(f"Invalid embedded asset size: {name}")
    with apk.open(name) as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != checksum:
        sys.exit(f"Invalid embedded asset checksum: {name}")


with ZipFile(args.apk) as apk:
    required = ["AndroidManifest.xml", "classes.dex"]
    for abi in ("arm64-v8a", "x86_64"):
        required.extend(f"lib/{abi}/{library}" for library in LIBRARIES)
    required.extend("lib/x86_64/" + name for name in (
        "libvexto_nemotron_avx2_exec.so", "libvexto_whisper_avx2_exec.so", "libvexto_llama_avx2_exec.so",
        "libnemo_speech_asr_avx2.so", "libnemo_speech_asr_c_avx2.so"))
    missing = set(required) - set(apk.namelist())
    if missing:
        sys.exit("Missing APK files: " + ", ".join(sorted(missing)))
    for name, (size, checksum) in ARTIFACTS.items():
        verify_asset(apk, f"assets/models/punctuation/{name}", size, checksum)
    overlap = json.loads((ROOT / "engine/src/main/overlap-assets/overlap/manifest.json").read_text())
    if json.loads(apk.read("assets/overlap/manifest.json")) != overlap:
        sys.exit("Embedded overlap manifest differs from prepared model")
    verify_asset(apk, "assets/" + overlap["path"], overlap["bytes"], overlap["sha256"])
    if args.require_models:
        if apk.read("assets/vexto-models/embedded") != b"1\n":
            sys.exit("Missing embedded offline pack marker")
        if apk.read("assets/quality/manifest.json") != MANIFEST.read_bytes():
            sys.exit("Embedded model manifest differs from repository pins")
        for artifact in json.loads(MANIFEST.read_bytes())["artifacts"].values():
            verify_asset(apk, "assets/vexto-models/" + artifact["path"], artifact["bytes"], artifact["sha256"])
    corrupt = apk.testzip()
    if corrupt:
        sys.exit(f"Corrupt APK file: {corrupt}")
print("Android APK verified for ARM64 and x86_64")
