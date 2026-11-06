# Native Nemotron 3 diarization on Android x86_64

The official NVIDIA NeMo-Speech.cpp runtime was cross-compiled and **executed on an Android API36 x86_64 emulator with 4096 MB RAM**, using CPU only. The build targets API26 and requires AVX2/FMA/F16C/SSE4.2 on the target CPU. This is a verified project port of the pinned sources; it is not a claim that upstream provides an Android release or that this binary runs on ARM phones.

The local smoke checks reported by the Android benchmark runner used two-speaker fixtures: clean 21 s in 0.4758 s with observed process peak about 138.99 MB; noisy 21 s in 0.5057 s with about 144.4 MB; repeated 84 s in 3.2924 s with about 161.3 MB. Speaker order was correct, the repeat retained two identities, and all 16 sampled word anchors had unambiguous assignments. These are smoke checks, not corpus DER or a general accuracy guarantee. Observed process memory excludes the application reserve and other live pipeline processes; it must not be presented as total application memory. Subsequent 30-minute repeated-input and three-speaker checks, including F32 Android runs, are recorded in [the pipeline results](pipeline-2gb.md).

## Source and model pins

Prepare these sources yourself before running the script. The recipe downloads nothing, installs no packages, invokes no Git, and runs no Android code.

| Component | Revision | License |
|---|---|---|
| [NVIDIA/NeMo-Speech.cpp](https://github.com/NVIDIA/NeMo-Speech.cpp/tree/536b17a41bef62d2a67a40ed3df61df26a4389fe) | `536b17a41bef62d2a67a40ed3df61df26a4389fe` | Apache-2.0 |
| Required [llama.cpp submodule](https://github.com/ggml-org/llama.cpp/tree/bd4f514db14d87fded667787a7a963bfbaa98e89) | `bd4f514db14d87fded667787a7a963bfbaa98e89` | MIT |
| [SentencePiece](https://github.com/google/sentencepiece/tree/31646a467d2051eb904e0b45de3a73e91fe1c1e3), pin from NVIDIA's static dependency script | `31646a467d2051eb904e0b45de3a73e91fe1c1e3` | Apache-2.0 and bundled third-party notices |
| [Official NVIDIA Q8 GGUF](https://huggingface.co/nvidia/Nemotron-3-Diarization/tree/f667ed73aee57d40cc39428eb768b4fd87a0a29e) | `f667ed73aee57d40cc39428eb768b4fd87a0a29e` | OpenMDW-1.1 |

Official source ZIP hashes used in the successful build:

| Archive URL | SHA256 |
|---|---|
| [NVIDIA pinned ZIP](https://codeload.github.com/NVIDIA/NeMo-Speech.cpp/zip/536b17a41bef62d2a67a40ed3df61df26a4389fe) | `ec91c81896e5b86b27e34b1765ecea3e4d6824e1f0784e9dde25de46faa3adbf` |
| [llama.cpp pinned ZIP](https://codeload.github.com/ggml-org/llama.cpp/zip/bd4f514db14d87fded667787a7a963bfbaa98e89) | `d74b36880a15ad7e6889dc1bb1db7489e929e4110cc613b9716cfc31bc33bbe7` |
| [SentencePiece pinned ZIP](https://codeload.github.com/google/sentencepiece/zip/31646a467d2051eb904e0b45de3a73e91fe1c1e3) | `ae0a258a823b895d3c3879d2f44a14c91abe1e3e2a67273ad12b90afdce00211` |

The model filename is `Nemotron-3-Diarization.q8_0.gguf`, **107012128 bytes**, SHA256 `08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1`. [Pinned download URL](https://huggingface.co/nvidia/Nemotron-3-Diarization/resolve/f667ed73aee57d40cc39428eb768b4fd87a0a29e/Nemotron-3-Diarization.q8_0.gguf). On 2026-11-06 the official HF repository contains only this GGUF; no official F16/F32 GGUF is published there. `.nemo` and `.safetensors` files are not interchangeable with GGUF. Weight size does not establish runtime memory or accuracy.

The official [diarization converter](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/536b17a41bef62d2a67a40ed3df61df26a4389fe/conversion/diarization.py) supports high-resolution v3 and F32/F16 outputs from the original checkpoint. The F32-linear/F16-convolution conversion below has now succeeded. Converting an already quantized Q8 file to F32 would not restore lost precision. Larger storage alone does not demonstrate a DER improvement.

Expected prepared source layout (rename the archive root directories accordingly):

```text
<SourcePath>/CMakeLists.txt                    # NeMo-Speech.cpp
<SourcePath>/llama.cpp/ggml/CMakeLists.txt     # pinned submodule
<SourcePath>/third_party/sentencepiece/       # separate pinned Google source
```

Optional server, translation, TTS, KenLM and gRPC submodules are unnecessary for this target. Preserve the upstream LICENSE/NOTICE/THIRD_PARTY_NOTICES and dependency notices when distributing binaries. The build exports installed runtime licenses; model terms must also accompany model distribution.

## Build

Use Windows PowerShell, an existing **Android NDK r28c**, CMake **3.26+** and Ninja available on `PATH`. The measured build used CMake3.29.2/Ninja1.12.0. NDK common headers and x86_64 API26 sysroot libraries are required. Keep downloaded dependencies, build directories and output outside tracked source files, for example in ignored `.agents/work/`.

```powershell
./tools/benchmarks/build_android_nemotron.ps1 `
  -NdkPath <existing-ndk-root> `
  -SourcePath <prepared-nemo-source-root> `
  -BuildPath <scratch-build-root> `
  -OutputPath <deployment-output-root> `
  -Jobs 4
```

The script builds PIC static SentencePiece, then `diarize_file` and its shared runtime dependencies. Upstream's diarization still shares the ASR runtime library and requires SentencePiece even with the ASR frontend disabled. The script uses `NEMO_SPEECH_GGML_PATCHED=OFF`, an upstream-supported stock GGML path; it does not apply NVIDIA's optional GPU/llama patches or invoke Git.

CPU flags: SSE4.2/AVX/AVX2/FMA/F16C on; native CPU detection, BMI2, AVX512, OpenMP, llamafile, BLAS, CUDA, Metal and Vulkan off. Microphone capture, unified CLI/model downloads, servers, ASR frontend, TTS, translation, tests and tools are off. The NDK C++ runtime is linked statically. This recipe is CPU-specific and must not be relabeled as an arm64 build.

The only required local source change is [nemotron-android-liblog.patch](../../tools/benchmarks/patches/nemotron-android-liblog.patch): the bundled protobuf-lite code calls `__android_log_write` on Android, so SentencePiece consumers need system `liblog`. The script accepts only the pinned `cmake/sentencepiece.cmake` SHA256 or its exact patched result, applies the five-line Android-only change idempotently, and rejects other versions. The adjacent patch content is also SHA256 guarded. Text checks canonicalize CRLF to LF before hashing to allow Windows checkouts; artifact hashes use actual bytes. These checks protect this patch's preimage, not the identity of every file in the supplied source tree; verify the source archives/pins separately.

Output contains `bin/diarize_file`, `lib/libnemo_speech_asr.so`, `lib/libnemo_speech_asr_c.so`, runtime licenses, and `build-manifest.json` with pins, hashes and ELF `NEEDED`. The successful stripped artifacts were respectively 232848, 4678680 and 77152 bytes. External dependencies were only Android system `libc.so`, `libm.so`, `libdl.so`, `liblog.so`; the ELF interpreter was `/system/bin/linker64`. No Termux dependency or separate libc++ shared library is needed for this build. The public recipe checks dependency names; the original build additionally passed a static undefined-symbol audit against API26 NDK stubs. Hashes may change with toolchains/build paths.

## Optional F32-linear / F16-convolution conversion

The original [pinned NVIDIA `.nemo`](https://huggingface.co/nvidia/Nemotron-3-Diarization/resolve/f667ed73aee57d40cc39428eb768b4fd87a0a29e/Nemotron-3-Diarization.nemo) is **198676480 bytes**, SHA256 `867c53f552998f772e5b5e5c082962ae85ee7ca5669c2bc17d7f615133d4e96d`. The official HF safetensors has Transformers names and split Q/K/V tensors and lacks the trained mel filterbank consumed by the native converter. Use the original `.nemo` with the official converter rather than manually remapping safetensors or expanding Q8 values.

A local conversion using the unchanged pinned NVIDIA `conversion.diarization.convert` succeeded in 0.797 s, excluding interpreter startup. It produced `Nemotron-3-Diarization.f32.gguf`: **395298848 bytes**, SHA256 `3339eca67ba8c7bc4a67b68797b60f8956e3ba1a34d5f84b14a0157ca96185b2`. The GGUF contains 360 tensors: **359 F32 and one F16 Conv1D kernel**. Upstream forces Conv1D to F16 because its CPU im2col implementation requires that type. Consequently `--outtype f32` does not mean every tensor or computation is F32. Subsequent Android F32 runs passed on clean, noisy and repeated inputs: the clean 21-second probe took 0.565 s with an observed peak of 431616000 bytes. Both full quality-pipeline runs also passed; see [pipeline results](pipeline-2gb.md). Corpus DER remains unmeasured. These are locally converted weights, not an official prebuilt F32 GGUF release.

Use an isolated, ignored dependency directory and an existing CPython3.12 x86_64 interpreter. Do not install into the global environment. The tested CPU-only Torch wheel is [2.9.1+cpu cp312 Windows](https://download-r2.pytorch.org/whl/cpu/torch-2.9.1%2Bcpu-cp312-cp312-win_amd64.whl), 110856378 bytes, SHA256 `a4e06b4f441675d26b462123c8a83e77c55f1ec8ebc081203be2db1ea8054add`. The extra isolated package directory stayed below 1 GB; CUDA, torchaudio, Transformers and the NeMo toolkit are unnecessary for this conversion. GGUF Python code comes directly from the pinned llama.cpp submodule.

```powershell
# Set these variables to existing local paths. Keep deps/output outside tracked files.
$python = <python-executable>
$source = <prepared-pinned-nemo-source-root>
$deps = <ignored-python-deps-directory>
$checkpoint = <verified-original-nemo-file>
$output = <output-f32-gguf-file>

& $python -m pip install --target $deps --no-deps --no-cache-dir --only-binary :all: `
  --index-url https://pypi.org/simple `
  'https://download-r2.pytorch.org/whl/cpu/torch-2.9.1%2Bcpu-cp312-cp312-win_amd64.whl#sha256=a4e06b4f441675d26b462123c8a83e77c55f1ec8ebc081203be2db1ea8054add' `
  numpy==2.3.4 PyYAML==6.0.3 filelock==3.20.0 typing_extensions==4.15.0 `
  sympy==1.14.0 mpmath==1.3.0 networkx==3.5 jinja2==3.1.6 MarkupSafe==3.0.3 `
  fsspec==2025.10.0 tqdm==4.67.1 requests==2.32.5 charset_normalizer==3.4.4 `
  idna==3.11 urllib3==2.5.0 certifi==2025.10.5
if ($LASTEXITCODE -ne 0) { throw 'Isolated dependency preparation failed.' }
$previousPythonPath = $env:PYTHONPATH
try {
  $env:PYTHONPATH = "$deps;$source/llama.cpp/gguf-py"
  & $python "$source/convert_model.py" $checkpoint --architecture diarization `
    --outtype f32 --outfile $output --no-build-quantizer
  if ($LASTEXITCODE -ne 0) { throw 'Conversion failed.' }
} finally { $env:PYTHONPATH = $previousPythonPath }
Get-FileHash -LiteralPath $output -Algorithm SHA256
```

`--outtype f16` is another supported experiment. The converted artifact above used F32 linear weights and the converter's mandatory F16 convolution. Record source hash, converter pin, dependency report, emitted dtype counts and output hash for each run. Output metadata can include the output filename, so a different filename or conversion toolchain can change the SHA even with identical source tensors. This preparation is weight conversion on the development host; actual application inference remains on Android.

## Running and memory limits

After deployment by a separate tool, use local 16-kHz mono PCM16 or float32 WAV:

```sh
LD_LIBRARY_PATH="$PWD/lib" ./bin/diarize_file \
  ./Nemotron-3-Diarization.q8_0.gguf ./input.wav \
  --gpu -1 --preset v3-offline --rttm recording > result.rttm
```

`--gpu -1` selects CPU. `--preset v3-offline` selects streaming geometry with approximately 30.4 s input-buffer latency, excluding computation. Do **not** add `--offline`: that means a single stateless full-attention pass. The example has no CPU thread flag; this pinned runtime defaults to four threads. `OMP_NUM_THREADS` does not set the GGML thread count in this OpenMP-disabled build.

The model supports at most eight speakers. Chunked processing preserves a speaker cache, but the example reads the entire WAV into a float vector. Its memory therefore grows with audio duration. The library also retains output probabilities until compaction: roughly a 20-minute trigger and a 10-minute retained tail. Compaction waits for a sufficiently long all-speaker-silent gap and can be delayed indefinitely without one; frozen segment storage also grows. A production app needs incremental WAV decoding, segment persistence and an explicit history policy before claiming a strict memory bound for arbitrary recordings. See [the pinned C API](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/536b17a41bef62d2a67a40ed3df61df26a4389fe/include/nemo_speech/diar.h), [stream history](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/536b17a41bef62d2a67a40ed3df61df26a4389fe/src/asr/diar/diar_pipeline.h), and [example](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/536b17a41bef62d2a67a40ed3df61df26a4389fe/examples/diarize_file.cpp).

This is a complete diarization alternative, not a larger embedding replacement. It is independent of sherpa-onnx2.0.1; the open sherpa Nemotron integration PR is not needed by this native recipe. The remaining validation is quality on diverse long multi-speaker recordings, overlap handling and the total simultaneous pipeline peak under 2000000000 bytes, including the application's memory reserve.
