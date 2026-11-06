<# Cross-build only: existing pinned whisper.cpp and NDK r28c, no downloads/ADB/Git. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$NdkPath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$SourcePath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$BuildPath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$OutputPath,
    [ValidateRange(1, 16)][int]$Jobs = 4,
    [switch]$Avx2
)
$ErrorActionPreference = 'Stop'
foreach ($directory in @($NdkPath, $SourcePath)) {
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw "Input directory missing: $directory" }
}
$NdkPath = (Resolve-Path -LiteralPath $NdkPath).Path
$SourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
$BuildPath = [IO.Path]::GetFullPath($BuildPath)
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
if ($BuildPath.TrimEnd([char]92, [char]47) -eq $OutputPath.TrimEnd([char]92, [char]47)) {
    throw 'BuildPath and OutputPath must differ.'
}
foreach ($directory in @($BuildPath, $OutputPath)) {
    if (Test-Path -LiteralPath $directory -PathType Leaf) { throw "Expected directory path, found file: $directory" }
    if ($directory.TrimEnd([char]92, [char]47) -eq $SourcePath.TrimEnd([char]92, [char]47) -or
        $directory.TrimEnd([char]92, [char]47) -eq $NdkPath.TrimEnd([char]92, [char]47)) {
        throw 'BuildPath/OutputPath must differ from SourcePath and NdkPath.'
    }
}
$toolchain = Join-Path $NdkPath 'build/cmake/android.toolchain.cmake'
$llvm = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64'
$readelf = Join-Path $llvm 'bin/llvm-readelf.exe'
$strip = Join-Path $llvm 'bin/llvm-strip.exe'
foreach ($file in @($toolchain, $readelf, "$NdkPath/source.properties", "$SourcePath/LICENSE", "$SourcePath/CMakeLists.txt", "$SourcePath/ggml/CMakeLists.txt", "$SourcePath/examples/cli/cli.cpp")) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required input missing: $file" }
}
if ((Get-Content -LiteralPath "$NdkPath/source.properties" -Raw) -notmatch '(?m)^Pkg\.Revision\s*=\s*28\.2\.13676358\s*$') {
    throw 'This recipe requires Android NDK r28c (28.2.13676358).'
}
# Check one stable source anchor; verify the full pinned source archive separately.
$sourceText = [IO.File]::ReadAllText("$SourcePath/CMakeLists.txt").Replace("`r`n", "`n")
$algorithm = [Security.Cryptography.SHA256]::Create()
try { $anchorHash = -join ($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($sourceText)) | ForEach-Object { $_.ToString('x2') }) }
finally { $algorithm.Dispose() }
if ($anchorHash -ne 'f4494d2da1353158b8a33b935abe6bbe76b8cee95d503fb16088224dd4755dcc') {
    throw 'Unexpected whisper.cpp CMakeLists.txt; use the documented source pin.'
}
$cmake = (Get-Command cmake -CommandType Application -ErrorAction Stop).Source
$ninja = (Get-Command ninja -CommandType Application -ErrorAction Stop).Source
$simd = if ($Avx2) { 'ON' } else { 'OFF' }
$options = @(
    '-S', $SourcePath, '-B', $BuildPath, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    '-DANDROID_ABI=x86_64', '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=OFF', '-DGGML_BACKEND_DL=OFF',
    '-DGGML_NATIVE=OFF', '-DGGML_OPENMP=OFF', '-DGGML_CCACHE=OFF',
    "-DGGML_AVX=$simd", "-DGGML_AVX2=$simd", "-DGGML_SSE42=$simd", "-DGGML_FMA=$simd", "-DGGML_F16C=$simd",
    '-DGGML_BMI2=OFF', '-DGGML_AVX512=OFF', '-DGGML_LLAMAFILE=OFF',
    '-DGGML_CUDA=OFF', '-DGGML_VULKAN=OFF', '-DGGML_METAL=OFF',
    '-DGGML_BLAS=OFF', '-DGGML_ACCELERATE=OFF',
    '-DWHISPER_BUILD_TESTS=OFF', '-DWHISPER_BUILD_EXAMPLES=ON',
    '-DWHISPER_BUILD_SERVER=OFF', '-DWHISPER_CURL=OFF',
    '-DWHISPER_BUILD_IS_DEV=OFF', '-DWHISPER_BUILD_NUMBER=0',
    '-DWHISPER_BUILD_COMMIT=d1be6fde11ac6e0407606b4e42fe72d34add8037',
    '-DCMAKE_DISABLE_FIND_PACKAGE_Git=TRUE', '-DGIT_EXECUTABLE:FILEPATH='
)
New-Item -ItemType Directory -Force -Path $BuildPath | Out-Null
$options | ConvertTo-Json | Set-Content -LiteralPath "$BuildPath/configure-arguments.json" -Encoding UTF8
& $cmake @options
if ($LASTEXITCODE -ne 0) { throw "CMake configure failed: exit $LASTEXITCODE" }
& $cmake --build $BuildPath --target whisper-cli --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw "Build failed: exit $LASTEXITCODE" }
New-Item -ItemType Directory -Force -Path "$OutputPath/bin", "$OutputPath/licenses" | Out-Null
$binary = Join-Path $OutputPath 'bin/whisper-cli'
Copy-Item -LiteralPath "$BuildPath/bin/whisper-cli" -Destination $binary -Force
$stripped = Test-Path -LiteralPath $strip -PathType Leaf
if ($stripped) {
    & $strip --strip-unneeded $binary
    if ($LASTEXITCODE -ne 0) { throw "ELF strip failed: exit $LASTEXITCODE" }
}
$inspection = (& $readelf --file-header --program-headers --dynamic $binary | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'ELF inspection failed.' }
if ($inspection -notmatch 'ELF64' -or $inspection -notmatch 'Advanced Micro Devices X86-64' -or $inspection -notmatch '/system/bin/linker64') {
    throw 'Output is not the expected Android x86_64 executable.'
}
$needed = @([regex]::Matches($inspection, '\(NEEDED\).*?\[(.*?)\]') | ForEach-Object { $_.Groups[1].Value })
foreach ($library in $needed) {
    if ($library -notin @('libc.so', 'libm.so', 'libdl.so')) { throw "Unexpected runtime dependency: $library" }
}
$inspection | Set-Content -LiteralPath "$OutputPath/elf-inspection.txt" -Encoding UTF8
Copy-Item -LiteralPath "$SourcePath/LICENSE" -Destination "$OutputPath/licenses/whisper-ggml-LICENSE" -Force
if (Test-Path -LiteralPath "$llvm/NOTICE" -PathType Leaf) {
    Copy-Item -LiteralPath "$llvm/NOTICE" -Destination "$OutputPath/licenses/NDK-NOTICE.toolchain" -Force
}
@{ status = 'cross_compiled_elf_checked_not_run'; abi = 'x86_64'; api = 26; ndk = 'r28c';
   source_revision_expected = 'd1be6fde11ac6e0407606b4e42fe72d34add8037';
   source_cmake_anchor_sha256 = $anchorHash; avx2 = [bool]$Avx2; cxx_runtime = 'static libc++';
   stripped = $stripped; needed = $needed; artifact = 'bin/whisper-cli';
   size_bytes = (Get-Item -LiteralPath $binary).Length;
   sha256 = (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant() } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$OutputPath/build-manifest.json" -Encoding UTF8
Write-Output $binary
