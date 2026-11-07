"""Verify that a standalone release APK contains the complete native runtime."""
import sys
import hashlib
from prepare_punctuation_models import ARTIFACTS
from zipfile import ZipFile


LIBRARIES = (
    "libvexto_nemotron_exec.so", "libvexto_whisper_exec.so", "libvexto_llama_exec.so",
    "libnemo_speech_asr.so", "libnemo_speech_asr_c.so",
    "libvexto_cpu.so", "libvexto_punct.so", "libvexto_emotion.so", "libvexto_emotion_jni.so", "libonnxruntime.so",
    "libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so",
)


with ZipFile(sys.argv[1]) as apk:
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
        value = apk.read(f"assets/models/punctuation/{name}")
        if len(value) != size or hashlib.sha256(value).hexdigest() != checksum:
            sys.exit(f"Invalid embedded punctuation asset: {name}")
    corrupt = apk.testzip()
    if corrupt:
        sys.exit(f"Corrupt APK file: {corrupt}")
print("Android APK verified for ARM64 and x86_64")
