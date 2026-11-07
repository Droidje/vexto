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
`libvexto_llama_exec.so`, alongside the NeMo shared dependencies. Whisper and
Qwen use the small app-owned public-API frontends in this directory, linked to
the pinned upstream libraries. These three PIE files are executed with ProcessBuilder;
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

Whisper and Qwen additionally support `--worker-dir DIR` persistent sessions.
`NativeProcessRunner.persistentExecutor(workDir)` returns a closeable
`LocalNativeExecutor`: allocate one per stage worker and close it before leaving
the stage. Each owns a dedicated spawning thread and child process, loads the
model/context once, and exchanges bounded JSON mailbox files (64 KiB requests,
one request in flight). No sockets, network, shell commands or audio through
Binder are involved. Worker diagnostics stay in `native-session-*/worker.stderr`;
per-request outputs keep their existing immutable artifact paths. A timeout,
cancellation or native request failure closes that worker rather than silently
reloading its model for subsequent chunks. Same-UID memory accounting includes
all concurrent workers; active peers are protected from orphan cleanup.

Whisper emits the native text/token/offset JSON consumed by the existing parser;
its context is retained with past-transcript conditioning disabled. Qwen retains
weights/context, clears KV memory and creates a fresh grammar/sampler for each
independent selection. Its fixed non-thinking single-user-message formatting is
the exact expansion of `SelectorPrompt.NonThinkingTemplate`; source selection
remains schema constrained and neither ASR original is rewritten. The frontends
also accept the bounded one-shot options used by existing engine adapters.

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
