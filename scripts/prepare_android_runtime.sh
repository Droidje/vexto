#!/usr/bin/env bash
# Reproduce the Android runtime and embedded optional punctuation model.
set -euo pipefail
cd "$(dirname "$0")/.."
ndk="$(cd "${1:?Provide the Android NDK directory}" && pwd)"
mkdir -p "${2:?Provide a scratch directory}"
work="$(cd "$2" && pwd)"
# NeMo's audited patch requires a fresh source tree.
sources="$(mktemp -d "$work/sources.XXXXXX")"
trap 'rm -rf "$sources"' EXIT

build="$(mktemp -d "$work/build.XXXXXX")"
pins=tools/native/sources.json
for name in nemotron sentencepiece whisper llama; do
    url="$(jq -er --arg name "$name" '.[$name].source_archive_url' "$pins")"
    expected="$(jq -er --arg name "$name" '.[$name].source_archive_sha256' "$pins")"
    curl --fail --location --retry 3 --silent --show-error "$url" -o "$sources/$name.zip"
    printf '%s  %s\n' "$expected" "$sources/$name.zip" | shasum -a 256 -c -
    mkdir "$sources/$name"
    unzip -q "$sources/$name.zip" -d "$sources/$name"
done
nemotron=("$sources"/nemotron/*/)
sentencepiece=("$sources"/sentencepiece/*/)
whisper=("$sources"/whisper/*/)
llama=("$sources"/llama/*/)
# The pinned NeMo archive includes an empty git submodule directory.
rmdir "${nemotron[0]}llama.cpp"
mv "${llama[0]%/}" "${nemotron[0]}llama.cpp"
patch --directory="${nemotron[0]}" -p1 < tools/benchmarks/patches/nemotron-android-liblog.patch

bash scripts/fetch_models.sh --aar-only
python3 scripts/prepare_emotion_runtime.py
python3 scripts/prepare_punctuation_models.py
python3 scripts/build_native_runtime.py --ndk "$ndk" \
    --nemotron-source "${nemotron[0]}" --sentencepiece-source "${sentencepiece[0]}" \
    --whisper-source "${whisper[0]}" --build-dir "$build" \
    --output-dir engine/src/main/jniLibs --jobs "${VEXTO_NATIVE_JOBS:-2}"
