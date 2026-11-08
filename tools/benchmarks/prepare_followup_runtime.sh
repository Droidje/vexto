#!/usr/bin/env bash
# Isolated CI only: reuse verified backends, compile current frontends, stage full offline models.
set -euo pipefail
cd "$(dirname "$0")/../.."
ndk="${1:?NDK}"
work="${2:?Existing precision preparation directory}"
apk="${3:?Successful native APK artifact}"
python3 - "$work" "$apk" <<'PY'
from pathlib import Path
import sys, zipfile
work, apk = Path(sys.argv[1]), Path(sys.argv[2])
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if name.startswith(('lib/x86_64/', 'lib/arm64-v8a/')) and name.endswith('.so'):
            target = Path('engine/src/main/jniLibs') / Path(name).relative_to('lib')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
PY
for name in sentencepiece whisper; do
    url="$(jq -er --arg n "$name" '.[$n].source_archive_url' tools/native/sources.json)"
    checksum="$(jq -er --arg n "$name" '.[$n].source_archive_sha256' tools/native/sources.json)"
    curl --fail --location --retry 3 --silent --show-error "$url" -o "$work/$name.zip"
    printf '%s  %s\n' "$checksum" "$work/$name.zip" | sha256sum -c -
    mkdir "$work/$name"
    unzip -q "$work/$name.zip" -d "$work/$name"
done
nemo=("$work"/nemotron/*/)
piece=("$work"/sentencepiece/*/)
whisper=("$work"/whisper/*/)
patch --directory="${nemo[0]}" -p1 < tools/benchmarks/patches/nemotron-android-liblog.patch
python3 scripts/build_native_runtime.py --ndk "$ndk" --nemotron-source "${nemo[0]}" \
    --sentencepiece-source "${piece[0]}" --whisper-source "${whisper[0]}" --component llama \
    --abi x86_64 --abi arm64-v8a --jobs 3 --build-dir "$work/current-native" --output-dir engine/src/main/jniLibs
python3 scripts/build_native_runtime.py --ndk "$ndk" --nemotron-source "${nemo[0]}" \
    --sentencepiece-source "${piece[0]}" --whisper-source "${whisper[0]}" --component llama \
    --abi x86_64 --avx2 --variant-suffix avx2 --jobs 3 --build-dir "$work/current-avx2" --output-dir engine/src/main/jniLibs
python3 - "$work" <<'PY'
from pathlib import Path
import sys
sys.path.insert(0, 'tools/benchmarks')
from probe_nemotron_precision import rebuild_frontend
for abi in ('x86_64', 'arm64-v8a'):
    rebuild_frontend(Path('engine/src/main/jniLibs') / abi, Path(sys.argv[1]) / 'converted/nemotron', abi)
PY
bash scripts/fetch_models.sh --aar-only
python3 scripts/prepare_emotion_runtime.py
python3 scripts/prepare_punctuation_models.py
for abi in x86_64 arm64-v8a; do
    cmake -S engine/src/main/cpp -B "$work/jni-$abi" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
    cmake --build "$work/jni-$abi" --parallel 3
    cp "$work/jni-$abi/"libvexto_*.so "engine/src/main/jniLibs/$abi/"
done
"$work/venv/bin/python" scripts/prepare_lid_models.py --work-dir "$work/lid-input" --output-dir "$work/converted/lid"
python3 scripts/prepare_quality_models.py --source-dir "$work/converted" --download --output-dir "$work/pack"
python3 tools/benchmarks/prepare_followup_models.py "$work/extra-whisper"
