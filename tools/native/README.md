# Android native runtime

`scripts/build_native_runtime.py` builds the exact revisions in `sources.json`
using NDK r28c, CMake >=3.26 and Ninja on macOS, Linux or Windows. It never downloads models, installs global
packages, runs inference or invokes ADB. Supply prepared source trees explicitly:

```text
python scripts/build_native_runtime.py --ndk NDK_DIR --nemotron-source NEMO_DIR --sentencepiece-source SPM_DIR --whisper-source WHISPER_DIR --build-dir IGNORED_BUILD_DIR --output-dir engine/src/main/jniLibs --jobs 2
```

The default ABIs are `arm64-v8a` and `x86_64`; repeat `--abi` or `--component` to
select a subset. `--avx2` is an explicit emulator optimization for x86_64; leave
it off for the generic x86 build. The prepared NeMo tree must contain the pinned llama.cpp checkout at `llama.cpp/` and have the audited
`tools/benchmarks/patches/nemotron-android-liblog.patch` applied. Source archive
SHA256 values and CMake anchors are recorded separately: an anchor alone is not
a full source tree integrity check.

The script stages installer-extracted PIE programs as
`libvexto_nemotron_exec.so`, `libvexto_whisper_exec.so` and
`libvexto_llama_exec.so`, alongside the NeMo shared dependencies. The llama
program is upstream `llama-completion`, the standalone completion/conversation
CLI at this revision. These three PIE files are executed with ProcessBuilder;
they must never be loaded with `System.loadLibrary`. APK configuration needs
legacy JNI packaging/extraction so the files are executable from
`ApplicationInfo.nativeLibraryDir`. Android execution must be tested in the APK;
successful cross-compilation is not that test.

Nemotron uses an app frontend over the unmodified public C ABI. It reads mono
PCM16/16 kHz WAV in 160 ms blocks, uses CPU and `v3-offline` streaming geometry,
and preserves all overlapping speaker rows. This removes the upstream example's
whole-WAV byte/float allocations; it does not change the neural model or its
postprocessing. All three CLI programs set parent-death SIGKILL so killing the
inference service also terminates its native child.

`NativeProcessRunner` samples combined RSS of this app UID every 100 ms,
including UI, inference service and native processes. The default observed
budget is 2,000,000,000 bytes with a 128 MiB reserve; this watchdog is not a hard
allocation cap. Cancellation, timeout, nonzero exit and memory rejection fail
the stage. Orphan recovery only terminates registered children whose UID,
executable and kernel start-time still match; it does not terminate arbitrary
same-UID services. Run ML stages sequentially and release the inference service
between stages.

Build logs and per-ABI `runtime-manifest.json` live in the chosen ignored build
directory. Binaries, build trees, dependencies and weights are not committed.
The checked-in `licenses/` files must be included as APK assets. Native code
licenses do not replace separate model-weight licenses.

The F32-linear Nemotron model is locally converted from the original NVIDIA
checkpoint; there is no claimed official download for this exact GGUF:

```text
python scripts/convert_nemotron_fullprecision.py --checkpoint ORIGINAL_NEMOTRON.nemo --nemo-source NEMO_DIR --deps ISOLATED_CPU_PYTHON_DEPS --output IGNORED_MODELS/Nemotron-3-Diarization.f32.gguf --check-only
```

Remove `--check-only` to convert. The script checks checkpoint/converter SHA256,
refuses existing outputs, validates 359 F32 tensors plus one F16 Conv1D kernel,
and requires the previously verified GGUF SHA256 before publishing. Conversion
is offline host preparation; model inference remains on Android. Runtime CPU
dependencies are Torch, NumPy, PyYAML and the pinned llama `gguf-py` source;
`--deps` may be repeated for isolated package directories.
