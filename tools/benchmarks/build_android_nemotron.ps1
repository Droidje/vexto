<#
Cross-build the pinned NeMo-Speech.cpp diarization example for Android x86_64.
Requires existing NDK r28c, CMake >=3.26 and Ninja on PATH. No downloads/install/ADB.
SourcePath must contain llama.cpp and third_party/sentencepiece at the documented pins.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$NdkPath,
    [Parameter(Mandatory)][string]$SourcePath,
    [Parameter(Mandatory)][string]$BuildPath,
    [Parameter(Mandatory)][string]$OutputPath,
    [ValidateRange(1, 16)][int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
$NdkPath = (Resolve-Path -LiteralPath $NdkPath).Path
$SourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
$BuildPath = [IO.Path]::GetFullPath($BuildPath)
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
$cmake = (Get-Command cmake -CommandType Application -ErrorAction Stop).Source
$ninja = (Get-Command ninja -CommandType Application -ErrorAction Stop).Source
$toolchain = Join-Path $NdkPath 'build/cmake/android.toolchain.cmake'
$llvm = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$readelf = Join-Path $llvm 'llvm-readelf.exe'
$strip = Join-Path $llvm 'llvm-strip.exe'
$spSource = Join-Path $SourcePath 'third_party/sentencepiece'
foreach ($required in @($toolchain, $readelf, $strip, "$SourcePath/CMakeLists.txt", "$SourcePath/llama.cpp/ggml/CMakeLists.txt", "$spSource/CMakeLists.txt")) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing prepared dependency: $required" }
}
function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-LfTextSha256([string]$Path) {
    $bytes = [Text.Encoding]::UTF8.GetBytes([IO.File]::ReadAllText($Path).Replace("`r`n", "`n"))
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return -join ($algorithm.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) }
    finally { $algorithm.Dispose() }
}
function Invoke-Cmake([string[]]$Arguments) {
    & $cmake @Arguments
    if ($LASTEXITCODE -ne 0) { throw "CMake failed: exit $LASTEXITCODE" }
}

# Exact source guard, not a general-purpose patcher. This is the adjacent patch.
$patch = Join-Path $PSScriptRoot 'patches/nemotron-android-liblog.patch'
if ((Get-LfTextSha256 $patch) -ne '94a1169c954badeace62c04e644b9407a388e64db766022c193f960b5fa8f0db') {
    throw 'Unexpected Android liblog patch content.'
}
$spCmake = Join-Path $SourcePath 'cmake/sentencepiece.cmake'
$beforeHash = '465c2a4d366659b15bfc3579f1b92dbce4a19be998905700361badde1d32ee19'
$afterHash = 'faf9803756a56e076004998aa9dfb2042f22a542e017ac54bb30b2cf63c8b26f'
if ((Get-LfTextSha256 $spCmake) -eq $beforeHash) {
    $text = [IO.File]::ReadAllText($spCmake).Replace("`r`n", "`n")
    $marker = "function(nemo_speech_link_sentencepiece target)`n"
    $replacement = $marker + '    # Bundled SentencePiece protobuf-lite uses __android_log_write on Android.' + "`n" +
        '    # liblog is part of the Android NDK/system and must be linked explicitly.' + "`n" +
        '    if(ANDROID)' + "`n" + '        target_link_libraries(${target} PRIVATE log)' + "`n" + '    endif()' + "`n"
    [IO.File]::WriteAllText($spCmake, $text.Replace($marker, $replacement), [Text.UTF8Encoding]::new($false))
}
if ((Get-LfTextSha256 $spCmake) -ne $afterHash) {
    throw 'Source differs from the pinned SentencePiece CMake preimage/result; inspect before building.'
}

$spBuild = Join-Path $BuildPath 'sentencepiece'
$build = Join-Path $BuildPath 'nemo'
$install = Join-Path $BuildPath 'install'
$deps = Join-Path $BuildPath 'deps'
$spPrefix = Join-Path $deps 'sentencepiece'
$common = @(
    '-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    '-DANDROID_ABI=x86_64', '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
    '-DCMAKE_DISABLE_FIND_PACKAGE_Git=TRUE', '-DGIT_EXECUTABLE:FILEPATH='
)
Invoke-Cmake (@('-S', $spSource, '-B', $spBuild) + $common + @(
    '-DSPM_BUILD_TEST=OFF', '-DSPM_ENABLE_SHARED=OFF', '-DSPM_ENABLE_TCMALLOC=OFF'
))
Invoke-Cmake @('--build', $spBuild, '--target', 'sentencepiece-static', '--parallel', "$Jobs")
New-Item -ItemType Directory -Force -Path "$spPrefix/lib", "$spPrefix/include" | Out-Null
Copy-Item -LiteralPath "$spBuild/src/libsentencepiece.a" -Destination "$spPrefix/lib/libsentencepiece.a" -Force
Copy-Item -LiteralPath "$spSource/src/sentencepiece_processor.h" -Destination "$spPrefix/include/sentencepiece_processor.h" -Force
$spLicenses = Join-Path $spPrefix 'share/licenses/nemo-speech/third_party/sentencepiece'
foreach ($name in @('LICENSE', 'third_party/absl/LICENSE', 'third_party/darts_clone/LICENSE', 'third_party/protobuf-lite/LICENSE')) {
    $destination = Join-Path $spLicenses $name
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath (Join-Path $spSource $name) -Destination $destination -Force
}

$flags = @(
    '-DBUILD_SHARED_LIBS=OFF', '-DCMAKE_INSTALL_LIBDIR=lib', "-DCMAKE_INSTALL_PREFIX=$install",
    "-DNEMO_SPEECH_DEPENDENCY_PREFIX=$deps", "-DSENTENCEPIECE_STATIC_LIB=$spPrefix/lib/libsentencepiece.a",
    "-DSENTENCEPIECE_INCLUDE_DIR=$spPrefix/include", '-DNEMO_SPEECH_GGML_PATCHED=OFF',
    '-DNEMO_SPEECH_BUILD_DIAR=ON', '-DNEMO_SPEECH_BUILD_EXAMPLES=ON',
    '-DNEMO_SPEECH_BUILD_ASR=OFF', '-DNEMO_SPEECH_BUILD_TTS=OFF', '-DNEMO_SPEECH_BUILD_NMT=OFF',
    '-DNEMO_SPEECH_BUILD_S2S=OFF', '-DNEMO_SPEECH_BUILD_SPEECHLM=OFF', '-DNEMO_SPEECH_BUILD_CLI=OFF',
    '-DNEMO_SPEECH_BUILD_MIC_CAPTURE=OFF', '-DNEMO_SPEECH_BUILD_HTTP=OFF', '-DNEMO_SPEECH_BUILD_GRPC=OFF',
    '-DNEMO_SPEECH_BUILD_TESTS=OFF', '-DBUILD_TESTING=OFF', '-DNEMO_SPEECH_BUILD_TOOLS=OFF',
    '-DNEMO_SPEECH_WITH_NORM=OFF', '-DNEMO_SPEECH_WITH_FLASHLIGHT=OFF',
    '-DGGML_CPU=ON', '-DGGML_NATIVE=OFF', '-DGGML_CPU_ALL_VARIANTS=OFF', '-DGGML_BACKEND_DL=OFF',
    '-DGGML_CCACHE=OFF', '-DGGML_LLAMAFILE=OFF', '-DGGML_OPENMP=OFF', '-DGGML_OPENMP_FETCH=OFF',
    '-DGGML_CUDA=OFF', '-DGGML_VULKAN=OFF', '-DGGML_METAL=OFF', '-DGGML_BLAS=OFF',
    '-DGGML_SSE42=ON', '-DGGML_AVX=ON', '-DGGML_AVX2=ON', '-DGGML_FMA=ON', '-DGGML_F16C=ON',
    '-DGGML_BMI2=OFF', '-DGGML_AVX_VNNI=OFF', '-DGGML_AVX512=OFF',
    '-DGGML_AVX512_VBMI=OFF', '-DGGML_AVX512_VNNI=OFF', '-DGGML_AVX512_BF16=OFF'
)
Invoke-Cmake (@('-S', $SourcePath, '-B', $build) + $common + $flags)
Invoke-Cmake @('--build', $build, '--target', 'diarize_file', '--parallel', "$Jobs")
Invoke-Cmake @('--install', $build)

# Export only runtime artifacts and licenses; static archives stay in BuildPath.
New-Item -ItemType Directory -Force -Path "$OutputPath/bin", "$OutputPath/lib", "$OutputPath/licenses" | Out-Null
Copy-Item -LiteralPath "$install/bin/diarize_file" -Destination "$OutputPath/bin/diarize_file" -Force
Get-ChildItem -LiteralPath "$install/lib" -Filter '*.so*' -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination "$OutputPath/lib/$($_.Name)" -Force
}
Copy-Item -LiteralPath "$install/share/licenses/nemo-speech" -Destination "$OutputPath/licenses" -Recurse -Force
$files = @(Get-ChildItem -LiteralPath "$OutputPath/bin", "$OutputPath/lib" -File)
$provided = @($files | ForEach-Object { $_.Name })
$systemAllowed = @('libc.so', 'libm.so', 'libdl.so', 'liblog.so')
$artifacts = foreach ($file in $files) {
    & $strip --strip-unneeded $file.FullName
    if ($LASTEXITCODE -ne 0) { throw "Strip failed: $($file.Name)" }
    $dynamic = (& $readelf --dynamic $file.FullName | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "ELF inspection failed: $($file.Name)" }
    $needed = @([regex]::Matches($dynamic, '\(NEEDED\).*?\[(.*?)\]') | ForEach-Object { $_.Groups[1].Value })
    foreach ($dependency in $needed) {
        if ($dependency -notin $provided -and $dependency -notin $systemAllowed) {
            throw "Dependency must be staged or reviewed: $dependency"
        }
    }
    @{ path = $file.FullName.Substring($OutputPath.Length + 1); size_bytes = (Get-Item -LiteralPath $file.FullName).Length;
       sha256 = Get-Sha256 $file.FullName; needed = $needed }
}
@{ status = 'cross_compiled_dependencies_checked_not_run'; abi = 'x86_64'; api = 26;
   source_revision = '536b17a41bef62d2a67a40ed3df61df26a4389fe';
   llama_revision = 'bd4f514db14d87fded667787a7a963bfbaa98e89';
   sentencepiece_revision = '31646a467d2051eb904e0b45de3a73e91fe1c1e3';
   patch_sha256 = Get-Sha256 $patch; patched_cmake_sha256 = Get-Sha256 $spCmake;
   cpu = 'AVX2/FMA/F16C/SSE42'; gpu = $false; artifacts = @($artifacts) } |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$OutputPath/build-manifest.json" -Encoding UTF8
Write-Host 'Cross-build complete. Android loading, inference quality and memory require separate measurements.'
