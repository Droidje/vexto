# Reproducing native Whisper for Android x86_64

This recipe builds `whisper-cli` for Android API26+ on a Windows host. It downloads nothing, installs no packages, invokes no Git/ADB, and runs no model. Existing local Android benchmarks use this source/toolchain configuration; this portable wrapper was checked by AST and input-validation tests without another compilation.

Prepare [whisper.cpp v1.9.5 at commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`](https://github.com/ggml-org/whisper.cpp/tree/d1be6fde11ac6e0407606b4e42fe72d34add8037), including its bundled `ggml/`, and Android **NDK r28c / 28.2.13676358**. CMake and Ninja must already be on `PATH` (tested versions: CMake3.29.2, Ninja1.12.0). The NDK needs its Windows LLVM toolchain, common headers and x86_64 API26 sysroot libraries.

| Pinned source archive | SHA256 |
|---|---|
| [whisper.cpp ZIP](https://codeload.github.com/ggml-org/whisper.cpp/zip/d1be6fde11ac6e0407606b4e42fe72d34add8037) | `6be93b881acb415c5a25d0ef85bb6a488099de0296e072e22a66d50f20ec40b9` |
| [NDK r28c Windows ZIP](https://dl.google.com/android/repository/android-ndk-r28c-windows.zip) | `6bec98ac2354d8a919760889a1a41d020132e5e8cfa1b1fe51610a72c36a466b` |

Keep downloaded sources, build directories and generated outputs in ignored local storage, for example `.agents/work/`. Use distinct build/output directories for the two CPU variants.

```powershell
./tools/benchmarks/build_android_whisper.ps1 `
  -NdkPath <existing-ndk-r28c-root> `
  -SourcePath <prepared-pinned-whisper-source-root> `
  -BuildPath <scratch-generic-build-root> `
  -OutputPath <generic-deployment-root> -Jobs 4
```

For the tested AVD's faster variant, supply separate paths and append `-Avx2`. This enables SSE4.2/AVX/AVX2/FMA/F16C and requires those features on the Android CPU. Without the switch they are disabled, retaining the x86_64 ABI baseline. Neither variant is an ARM phone binary.

Both variants set `ANDROID_ABI=x86_64`, `ANDROID_PLATFORM=android-26`, `ANDROID_STL=c++_static`, `BUILD_SHARED_LIBS=OFF`, `GGML_BACKEND_DL=OFF`, and Release mode. Native host CPU detection, OpenMP, ccache, BMI2, AVX512, llamafile, CUDA, Vulkan, Metal, BLAS and Accelerate are off. Tests/server/CURL are off; examples are enabled only to build target `whisper-cli`. Git discovery is disabled and build metadata records the pinned commit.

The script validates input directories/files, NDK revision and a LF-canonicalized hash of the source's top-level CMakeLists. This source anchor is not a substitute for verifying the complete archive/pin. It copies only the executable to `OutputPath/bin/whisper-cli`, strips it if NDK `llvm-strip` exists, and records ELF inspection, size/SHA256, CPU variant and dependencies in `build-manifest.json`. Expected external dependencies are only Android system `libc.so`, `libm.so`, `libdl.so`; libc++ and GGML are static. Source/NDK license notices accompany the output.

The compiled executable is an Android ELF using `/system/bin/linker64`, not a Windows executable or a desktop Linux/glibc binary. A successful build does not establish model accuracy, RTF or memory consumption: those require separate Android inference measurements. This build recipe does not choose model weights, decoder beam settings or flash attention, and does not execute inference.
