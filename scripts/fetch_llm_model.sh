#!/usr/bin/env bash
# Скачивает GGUF-модель для модуля :llm в llm/src/main/assets/models/ (в .gitignore)
# и проверяет sha256. Модель попадает в APK, при первом запуске копируется в filesDir.
#
#   scripts/fetch_llm_model.sh            # модель по умолчанию
#   scripts/fetch_llm_model.sh qwen2.5    # запасная
set -euo pipefail

case "${1:-qwen3}" in
    qwen3)
        FILE="Qwen3-0.6B-Q8_0.gguf"
        URL="https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf"
        SHA256="9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
        ;;
    qwen2.5)
        FILE="qwen2.5-0.5b-instruct-q4_k_m.gguf"
        URL="https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        SHA256="74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        ;;
    *)
        echo "неизвестная модель: $1 (qwen3 | qwen2.5)" >&2
        exit 1
        ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/llm/src/main/assets/models"
mkdir -p "$DIR"
DEST="$DIR/$FILE"

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

if [ -f "$DEST" ] && [ "$(sha "$DEST")" = "$SHA256" ]; then
    echo "модель уже на месте: $DEST"
else
    # Докачка с продолжением: на нестабильной сети curl обрывается на сотнях мегабайт.
    for attempt in $(seq 1 30); do
        if curl -fSL -C - --retry 5 --retry-all-errors --connect-timeout 20 -o "$DEST.part" "$URL"; then
            break
        fi
        echo "обрыв загрузки, попытка $attempt" >&2
        [ "$attempt" = 30 ] && exit 1
    done
    if [ "$(sha "$DEST.part")" != "$SHA256" ]; then
        echo "sha256 не совпал для $FILE" >&2
        rm -f "$DEST.part"
        exit 1
    fi
    mv "$DEST.part" "$DEST"
fi

# В APK должна лежать ровно одна модель.
find "$DIR" -name '*.gguf' ! -name "$FILE" -delete
echo "готово: $DEST"
