"""Build the standalone GigaEmo Android launcher; no deployment or inference.

Python standard library, JDK 11+, Android SDK build-tools/D8 and platform 35.
Dependencies and outputs stay in --work-dir, outside tracked source directories.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ORT_VERSION = "1.28.0"
ORT_URL = (
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/"
    f"onnxruntime-android/{ORT_VERSION}/onnxruntime-android-{ORT_VERSION}.aar"
)
ORT_SHA256 = "f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9"


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sdk", type=Path, required=True)
    p.add_argument("--java-home", type=Path, required=True)
    p.add_argument("--work-dir", type=Path, required=True)
    p.add_argument("--build-tools", default="36.1.0")
    p.add_argument("--platform", default="android-36")
    a = p.parse_args()
    work = a.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    classes, temporary = work / "classes", work / "tmp"
    classes.mkdir(exist_ok=True)
    temporary.mkdir(exist_ok=True)
    aar = work / f"onnxruntime-android-{ORT_VERSION}.aar"
    if not aar.exists():
        with urllib.request.urlopen(ORT_URL) as response, aar.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
    if sha256(aar) != ORT_SHA256:
        raise ValueError("ORT AAR checksum mismatch; refusing to build")
    ort = work / "ort"
    with zipfile.ZipFile(aar) as archive:
        for name in ("classes.jar", "jni/x86_64/libonnxruntime.so", "jni/x86_64/libonnxruntime4j_jni.so"):
            archive.extract(name, ort)
    exe = ".exe" if (a.java_home / "bin/java.exe").exists() else ""
    java = a.java_home / f"bin/java{exe}"
    javac = a.java_home / f"bin/javac{exe}"
    source = Path(__file__).with_name("GigaEmo.java")
    frontend = Path(__file__).resolve().parents[4] / "engine/src/main/java/com/vexto/engine/emotion/GigaAmMel.java"
    subprocess.run([str(javac), "--release", "11", "-cp", str(ort / "classes.jar"),
                    "-d", str(classes), str(source), str(frontend)], check=True)
    app_jar = work / "gigaemo-classes.jar"
    with zipfile.ZipFile(app_jar, "w", zipfile.ZIP_DEFLATED) as archive:
        # Only this launcher's classes; do not include stale unrelated files.
        for path in sorted(classes.glob("GigaEmo*.class")):
            archive.write(path, path.name)
        path = classes / "com/vexto/engine/emotion/GigaAmMel.class"
        archive.write(path, path.relative_to(classes).as_posix())
    output = work / "gigaemo.jar"
    d8 = a.sdk / "build-tools" / a.build_tools / "lib/d8.jar"
    android = a.sdk / "platforms" / a.platform / "android.jar"
    subprocess.run([str(java), f"-Djava.io.tmpdir={temporary}", "-cp", str(d8),
                    "com.android.tools.r8.D8", "--min-api", "26", "--lib", str(android),
                    "--output", str(output), str(app_jar), str(ort / "classes.jar")], check=True)
    artifacts = [output, ort / "jni/x86_64/libonnxruntime.so",
                 ort / "jni/x86_64/libonnxruntime4j_jni.so"]
    manifest = {
        "runtime": f"onnxruntime-android {ORT_VERSION}", "abi": "x86_64", "min_api": 26,
        "aar_url": ORT_URL, "aar_sha256": ORT_SHA256,
        "source_sha256": sha256(source), "frontend_sha256": sha256(frontend), "build_only": True,
        "android_inference_verified": False,
        "files": [{"file": str(f.relative_to(work)).replace("\\", "/"),
                   "bytes": f.stat().st_size, "sha256": sha256(f)} for f in artifacts],
    }
    (work / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
