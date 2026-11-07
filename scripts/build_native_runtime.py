#!/usr/bin/env python3
"""Cross-build pinned Android PIE CLIs for installer-extracted jniLibs. No ADB/inference/downloads.

Prepared sources are explicit inputs. Native binaries/models/build trees stay ignored.
The sources.json anchors reject unexpected CMake input, but do not replace a full
source archive provenance check when initially acquiring the pinned revisions.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "tools/native"


def digest(path: Path, text: bool = False) -> str:
    data = path.read_text(encoding="utf-8").replace("\r\n", "\n").encode() if text else path.read_bytes()
    return hashlib.sha256(data).hexdigest()


def run(argv: list[str | Path], log: Path) -> str:
    command = [str(item) for item in argv]
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("a", encoding="utf-8") as output:
        output.write(json.dumps(command) + "\n")
        output.flush()
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"native build command failed ({result.returncode}); inspect {log}")
    return ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("ndk", "nemotron-source", "sentencepiece-source", "whisper-source", "build-dir", "output-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), action="append")
    parser.add_argument("--component", choices=("nemotron", "whisper", "llama"), action="append")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--avx2", action="store_true", help="x86_64 emulator only; generic default")
    parser.add_argument("--variant-suffix", choices=("avx2",), help="Stage accelerated companions alongside baseline libraries")
    args = parser.parse_args()
    if args.variant_suffix and (not args.avx2 or args.abi != ["x86_64"]):
        parser.error("variant-suffix requires --avx2 --abi x86_64")
    if not 1 <= args.jobs <= 8:
        parser.error("jobs must be 1..8")
    cmake, ninja = shutil.which("cmake"), shutil.which("ninja")
    if not cmake or not ninja:
        parser.error("CMake >=3.26 and Ninja must be on PATH")
    ndk = args.ndk.resolve()
    if "28.2.13676358" not in (ndk / "source.properties").read_text():
        parser.error("NDK r28c required")
    sources = {"nemotron": args.nemotron_source.resolve(), "llama": args.nemotron_source.resolve() / "llama.cpp",
               "sentencepiece": args.sentencepiece_source.resolve(), "whisper": args.whisper_source.resolve()}
    pins = json.loads((NATIVE / "sources.json").read_text(encoding="utf-8"))
    for name, source in sources.items():
        if digest(source / "CMakeLists.txt", text=True) != pins[name]["cmake_sha256_lf"]:
            raise ValueError(f"unexpected {name} source anchor; use pinned revision")
    subprocess.run([sys.executable, str(NATIVE / "patch_diar_threads.py"), str(sources["nemotron"])], check=True)
    # The NDK liblog link is the existing audited minimal upstream build patch.
    sentence_cmake = sources["nemotron"] / "cmake/sentencepiece.cmake"
    if digest(sentence_cmake, text=True) != "faf9803756a56e076004998aa9dfb2042f22a542e017ac54bb30b2cf63c8b26f":
        raise ValueError("apply documented tools/benchmarks/patches/nemotron-android-liblog.patch first")
    host_tools = {"darwin": "darwin-x86_64", "linux": "linux-x86_64", "win32": "windows-x86_64"}
    host = host_tools.get(sys.platform)
    if host is None:
        parser.error(f"unsupported Android NDK host: {sys.platform}")
    llvm = ndk / "toolchains" / "llvm" / "prebuilt" / host / "bin"
    suffix = ".exe" if sys.platform == "win32" else ""
    readelf, strip = llvm / f"llvm-readelf{suffix}", llvm / f"llvm-strip{suffix}"
    if not readelf.is_file() or not strip.is_file():
        parser.error(f"missing NDK LLVM host tools: {llvm}")
    for abi in args.abi or ("arm64-v8a", "x86_64"):
        build = args.build_dir.resolve() / abi
        output = args.output_dir.resolve() / abi
        build.mkdir(parents=True, exist_ok=True)
        output.mkdir(parents=True, exist_ok=True)
        log = build / "build.log"
        common = ["-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}",
                  f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
                  f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-28", "-DANDROID_STL=c++_static",
                  "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", "-DCMAKE_SKIP_RPATH=ON",
                  "-DCMAKE_DISABLE_FIND_PACKAGE_Git=TRUE", "-DGIT_EXECUTABLE:FILEPATH=",
                  "-DBUILD_SHARED_LIBS=OFF", "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF",
                  "-DGGML_CCACHE=OFF", "-DGGML_LLAMAFILE=OFF", "-DGGML_BACKEND_DL=OFF",
                  "-DGGML_CUDA=OFF", "-DGGML_VULKAN=OFF", "-DGGML_METAL=OFF", "-DGGML_BLAS=OFF"]
        if abi == "x86_64":
            simd = "ON" if args.avx2 else "OFF"
            common += [f"-DGGML_{feature}={simd}" for feature in ("AVX", "AVX2", "FMA", "F16C", "SSE42")]
            common += ["-DGGML_AVX512=OFF", "-DGGML_BMI2=OFF", "-DGGML_AVX_VNNI=OFF"]
        components = args.component or ("nemotron", "whisper", "llama")
        for component in components:
            print(f"building {abi}/{component}", flush=True)
            target_dir = build / (component + "-upstream" if component == "nemotron" else component)
            if component == "nemotron":
                sp = build / "sentencepiece"
                run([cmake, "-S", sources["sentencepiece"], "-B", sp] + common +
                    ["-DSPM_BUILD_TEST=OFF", "-DSPM_ENABLE_SHARED=OFF", "-DSPM_ENABLE_TCMALLOC=OFF"], log)
                run([cmake, "--build", sp, "--target", "sentencepiece-static", "--parallel", args.jobs], log)
                configure = [cmake, "-S", sources["nemotron"], "-B", target_dir] + common + [
                    f"-DCMAKE_PROJECT_INCLUDE={NATIVE / 'CMakeLists.txt'}",
                    f"-DNEMO_SOURCE={sources['nemotron']}", f"-DNEMO_SPEECH_LLAMA_CPP_SOURCE_DIR={sources['llama']}", f"-DSENTENCEPIECE_STATIC_LIB={sp / 'src/libsentencepiece.a'}",
                    f"-DSENTENCEPIECE_INCLUDE_DIR={sources['sentencepiece'] / 'src'}",
                    "-DNEMO_SPEECH_BUILD_DIAR=ON", "-DNEMO_SPEECH_BUILD_EXAMPLES=OFF",
                    "-DNEMO_SPEECH_GGML_PATCHED=OFF", "-DNEMO_SPEECH_WITH_NORM=OFF", "-DNEMO_SPEECH_WITH_FLASHLIGHT=OFF"]
                configure += [f"-DNEMO_SPEECH_BUILD_{feature}=OFF" for feature in
                              ("ASR", "TTS", "NMT", "S2S", "SPEECHLM", "CLI", "MIC_CAPTURE", "HTTP", "GRPC", "TESTS", "TOOLS")]
                target, binary = "vexto_nemotron_exec", target_dir / "vexto_nemotron_exec"
            elif component == "whisper":
                configure = [cmake, "-S", sources["whisper"], "-B", target_dir] + common + [
                    f"-DCMAKE_PROJECT_INCLUDE={NATIVE / 'GuardCli.cmake'}",
                    "-DWHISPER_BUILD_TESTS=OFF", "-DWHISPER_BUILD_EXAMPLES=ON", "-DWHISPER_BUILD_SERVER=OFF",
                    "-DWHISPER_CURL=OFF", "-DWHISPER_BUILD_IS_DEV=OFF", "-DWHISPER_BUILD_NUMBER=0",
                    "-DWHISPER_BUILD_COMMIT=" + pins["whisper"]["revision"]]
                target, binary = "vexto_whisper_exec", target_dir / "bin/vexto_whisper_exec"
            else:
                configure = [cmake, "-S", sources["llama"], "-B", target_dir] + common + [
                    f"-DCMAKE_PROJECT_INCLUDE={NATIVE / 'GuardCli.cmake'}",
                    "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=ON", "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_CURL=OFF", "-DLLAMA_OPENSSL=OFF", "-DLLAMA_BUILD_COMMON=ON"]
                target, binary = "vexto_llama_exec", target_dir / "bin/vexto_llama_exec"
            if args.variant_suffix and component == "nemotron":
                configure += ["-DVEXTO_VARIANT_SUFFIX=" + args.variant_suffix]
            run(configure, log)
            run([cmake, "--build", target_dir, "--target", target, "--parallel", args.jobs], log)
            variant = "_" + args.variant_suffix if args.variant_suffix else ""
            destination = output / f"libvexto_{component}{variant}_exec.so"
            shutil.copy2(binary, destination)
            run([strip, "--strip-unneeded", destination], log)
            if component == "nemotron":
                for library in target_dir.rglob("*.so"):
                    shutil.copy2(library, output / library.name)
                    run([strip, "--strip-unneeded", output / library.name], log)
        artifacts = []
        for file in sorted(output.glob("*.so")):
            elf = subprocess.check_output([str(readelf), "--file-header", "--program-headers", "--dynamic", str(file)], text=True)
            needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", elf)
            for name in needed:
                if name not in {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"} and not (output / name).exists():
                    raise ValueError(f"unstaged dependency {name} of {file.name}")
            if file.name.startswith("libvexto_") and "/system/bin/linker64" not in elf:
                raise ValueError("packaged executable must be Android PIE with linker64")
            artifacts.append({"file": file.name, "size_bytes": file.stat().st_size, "sha256": digest(file), "needed": needed})
        manifest = {"status": "cross_compiled_not_executed", "abi": abi, "api": 28, "ndk": "r28c",
                    "x86_avx2": args.avx2 and abi == "x86_64", "sources": pins,
                    "frontend_sha256": digest(NATIVE / "nemotron_main.cpp"),
                    "persistent_whisper_sha256": digest(NATIVE / "whisper_main.cpp"),
                    "persistent_llama_sha256": digest(NATIVE / "llama_main.cpp"),
                    "worker_mailbox_sha256": digest(NATIVE / "worker_mailbox.h"),
                    "parent_death_sha256": digest(NATIVE / "parent_death.cpp"),
                    "build_recipe_sha256": digest(Path(__file__)), "artifacts": artifacts}
        (build / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        license_dir = NATIVE / "licenses"
        license_dir.mkdir(parents=True, exist_ok=True)
        for name, source in sources.items():
            for notice in ("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"):
                if (source / notice).is_file():
                    shutil.copy2(source / notice, license_dir / (name + "-" + notice))
        for nested in ("third_party/absl/LICENSE", "third_party/darts_clone/LICENSE", "third_party/protobuf-lite/LICENSE"):
            source = sources["sentencepiece"] / nested
            shutil.copy2(source, license_dir / ("sentencepiece-" + nested.replace("/", "-")))
        print(f"staged {abi}: {len(artifacts)} ELF artifacts; validate actual Android exec separately", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
