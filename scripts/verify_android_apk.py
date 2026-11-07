"""Verify that a standalone release APK contains the complete native runtime."""
import sys
from zipfile import ZipFile


LIBRARIES = (
    "libvexto_nemotron_exec.so", "libvexto_whisper_exec.so", "libvexto_llama_exec.so",
    "libnemo_speech_asr.so", "libnemo_speech_asr_c.so",
    "libvexto_emotion.so", "libvexto_emotion_jni.so", "libonnxruntime.so",
    "libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so",
)


with ZipFile(sys.argv[1]) as apk:
    required = ["AndroidManifest.xml", "classes.dex"]
    for abi in ("arm64-v8a", "x86_64"):
        required.extend(f"lib/{abi}/{library}" for library in LIBRARIES)
    missing = set(required) - set(apk.namelist())
    if missing:
        sys.exit("Missing APK files: " + ", ".join(sorted(missing)))
    corrupt = apk.testzip()
    if corrupt:
        sys.exit(f"Corrupt APK file: {corrupt}")
print("Android APK verified for ARM64 and x86_64")
