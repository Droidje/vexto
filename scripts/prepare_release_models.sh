#!/usr/bin/env bash
# Prepare the pinned offline release pack on a Linux x86_64 CPython 3.12 runner.
set -euo pipefail

python3 -c 'import platform, sys; assert platform.system() == "Linux" and platform.machine() == "x86_64" and sys.version_info[:2] == (3, 12), "requires Linux x86_64 and Python 3.12"'
mkdir "${1:?Provide a new scratch directory}"
work="$(cd "$1" && pwd)"
cd "$(dirname "$0")/.."

python3 -m venv "$work/venv"
python="$work/venv/bin/python"
cat > "$work/requirements.txt" <<'EOF'
torch @ https://download-r2.pytorch.org/whl/cpu/torch-2.9.1%2Bcpu-cp312-cp312-manylinux_2_28_x86_64.whl --hash=sha256:7417d8c565f219d3455654cb431c6d892a3eb40246055e14d645422de13b9ea1
numpy==2.3.4 --hash=sha256:4121c5beb58a7f9e6dfdee612cb24f4df5cd4db6e8261d7f4d7450a997a65d6a
PyYAML==6.0.3 --hash=sha256:ba1cc08a7ccde2d2ec775841541641e4548226580ab850948cbfda66a1befcdc
filelock==3.20.0 --hash=sha256:339b4732ffda5cd79b13f4e2711a31b0365ce445d95d243bb996273d072546a2
typing_extensions==4.15.0 --hash=sha256:f0fa19c6845758ab08074a0cfa8b7aecb71c999ca73d62883bc25cc018c4e548
sympy==1.14.0 --hash=sha256:e091cc3e99d2141a0ba2847328f5479b05d94a6635cb96148ccb3f34671bd8f5
mpmath==1.3.0 --hash=sha256:a0b2b9fe80bbcd81a6647ff13108738cfb482d481d826cc0e02f5b35e5c88d2c
networkx==3.5 --hash=sha256:0030d386a9a06dee3565298b4a734b68589749a544acbb6c412dc9e2489ec6ec
jinja2==3.1.6 --hash=sha256:85ece4451f492d0c13c5dd7c13a64681a86afae63a5f347908daf103ce6d2f67
MarkupSafe==3.0.3 --hash=sha256:d6dd0be5b5b189d31db7cda48b91d7e0a9795f31430b7f271219ab30f1d3ac9d
fsspec==2025.10.0 --hash=sha256:7c7712353ae7d875407f97715f0e1ffcc21e33d5b24556cb1e090ae9409ec61d
tqdm==4.67.1 --hash=sha256:26445eca388f82e72884e0d580d5464cd801a3ea01e63e5601bdff9ba6a48de2
requests==2.32.5 --hash=sha256:2462f94637a34fd532264295e186976db0f5d453d1cdd31473c85a6a161affb6
charset_normalizer==3.4.4 --hash=sha256:7a32c560861a02ff789ad905a2fe94e3f840803362c84fecf1851cb4cf3dc37f --hash=sha256:11d694519d7f29d6cd09f6ac70028dba10f92f6cdd059096db198c283794ac86
idna==3.11 --hash=sha256:771a87f49d9defaf64091e6e6fe9c18d4833f140bd19464795bc32d966ca37ea
urllib3==2.5.0 --hash=sha256:e6b01673c0fa6a13e374b50871808eb3bf7046c4b125b216f6bf1cc604cff0dc
certifi==2025.10.5 --hash=sha256:0f212c2744a9bb6de0c56639a6f68afe01ecd92d91f14ae897c4fe7bbeeef0de
EOF
"$python" -m pip --isolated --disable-pip-version-check install --no-deps --no-cache-dir \
    --only-binary :all: --require-hashes --index-url https://pypi.org/simple \
    --retries 3 --timeout 60 -r "$work/requirements.txt"

download() {
    curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
        --connect-timeout 20 --max-time 300 --retry-max-time 600 "$1" -o "$2"
}

pins=tools/native/sources.json
for name in nemotron llama; do
    url="$(jq -er --arg name "$name" '.[$name].source_archive_url' "$pins")"
    checksum="$(jq -er --arg name "$name" '.[$name].source_archive_sha256' "$pins")"
    archive="$work/$name.zip"
    download "$url" "$archive"
    printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
    unzip -q "$archive" -d "$work/$name"
done
nemotron=("$work"/nemotron/*/)
llama=("$work"/llama/*/)
rmdir "${nemotron[0]}llama.cpp"
mv "${llama[0]%/}" "${nemotron[0]}llama.cpp"

checkpoint="$work/Nemotron-3-Diarization.nemo"
download 'https://huggingface.co/nvidia/Nemotron-3-Diarization/resolve/f667ed73aee57d40cc39428eb768b4fd87a0a29e/Nemotron-3-Diarization.nemo' "$checkpoint"
"$python" scripts/convert_nemotron_fullprecision.py --checkpoint "$checkpoint" \
    --nemo-source "${nemotron[0]}" --output "$work/converted/nemotron/Nemotron-3-Diarization.f32.gguf"

for attempt in 1 2 3; do
    if "$python" scripts/prepare_quality_models.py --source-dir "$work/converted" --download --output-dir "$work/pack"; then
        exit 0
    fi
    if [[ "$attempt" != 3 ]]; then
        sleep "$((attempt * 5))"
    fi
done
exit 1
