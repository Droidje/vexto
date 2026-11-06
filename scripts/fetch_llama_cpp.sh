#!/usr/bin/env bash
# Скачивает закреплённую версию llama.cpp в llm/third_party/llama.cpp (каталог в .gitignore).
# Gradle вызывает скрипт сам перед первой сборкой модуля :llm.
set -euo pipefail

TAG="v0.7.0"
SHA256="55d67acf3ad2ad747a9bbeb75f9f0e173dbcc0d1753a471038a90c819421c5e1"
URL="https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/${TAG}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/llm/third_party/llama.cpp"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ -f "$DEST/CMakeLists.txt" ]; then
    echo "llama.cpp уже на месте: $DEST"
    exit 0
fi

for attempt in 1 2 3 4 5; do
    if curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o "$TMP/src.tar.gz" "$URL"; then
        break
    fi
    echo "Загрузка llama.cpp не удалась, попытка $attempt" >&2
    [ "$attempt" = 5 ] && exit 1
done

ACTUAL="$(shasum -a 256 "$TMP/src.tar.gz" | cut -d' ' -f1)"
if [ "$ACTUAL" != "$SHA256" ]; then
    echo "sha256 не совпал: ожидался $SHA256, получен $ACTUAL" >&2
    exit 1
fi

mkdir -p "$TMP/src"
tar -xzf "$TMP/src.tar.gz" -C "$TMP/src" --strip-components=1
mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
mv "$TMP/src" "$DEST"
echo "llama.cpp $TAG распакован в $DEST"
