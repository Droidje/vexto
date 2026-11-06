#!/usr/bin/env bash
# Скачивает AAR sherpa-onnx и модели движка (выбор по замерам bench, см. engine/README.md), проверяет sha256 и пишет манифест.
#
#   scripts/fetch_models.sh [--asr ID] [--emb ID] [--threshold X] [--aar-only]
#
# ASR: gigaam-v3-rnnt (по умолчанию), gigaam-v3-rnnt-punct (своя пунктуация, WER хуже на 0,5–4 п.п.),
# gigaam-v2-rnnt. Эмбеддинги спикера: eres2net (по умолчанию), titanet-small, campplus.
# Порог кластеризации по умолчанию 0,35, рабочий диапазон для eres2net 0,35–0,40.
# Скачанное кэшируется в $VEXTO_MODEL_CACHE (по умолчанию ~/.cache/vexto-models).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${VEXTO_MODEL_CACHE:-$HOME/.cache/vexto-models}"
ASSETS="$ROOT/engine/src/main/assets/models"
HF=https://huggingface.co
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download

ASR=gigaam-v3-rnnt
EMB=eres2net
THRESHOLD=0.35
AAR_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --asr) ASR="$2"; shift 2 ;;
    --emb) EMB="$2"; shift 2 ;;
    --threshold) THRESHOLD="$2"; shift 2 ;;
    --aar-only) AAR_ONLY=1; shift ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
  esac
done

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

# fetch URL SHA256 TARGET: качает в кэш по хешу, кладёт жёсткую ссылку в TARGET
fetch() {
  local url="$1" sum="$2" target="$3" cached="$CACHE/$2"
  mkdir -p "$CACHE" "$(dirname "$target")"
  if [ ! -f "$cached" ] || [ "$(sha256 "$cached")" != "$sum" ]; then
    echo "скачиваю $url"
    curl -fL --retry 5 --retry-all-errors -C - -o "$cached.part" "$url"
    if [ "$(sha256 "$cached.part")" != "$sum" ]; then
      echo "sha256 не совпал: $url" >&2
      rm -f "$cached.part"
      exit 1
    fi
    mv "$cached.part" "$cached"
  fi
  rm -f "$target"
  ln "$cached" "$target" 2>/dev/null || cp "$cached" "$target"
}

fetch "$GH/v2.0.1/sherpa-onnx-2.0.1.aar" \
  a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7 "$ROOT/engine/libs/sherpa-onnx-2.0.1.aar"
[ "$AAR_ONLY" = 1 ] && exit 0

case "$ASR" in
  gigaam-v3-rnnt-punct)
    REPO=csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16; PUNCT=true
    ENC=369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf
    DEC=38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4
    JOIN=602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c
    TOK=39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7 ;;
  gigaam-v3-rnnt)
    REPO=csukuangfj/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16; PUNCT=false
    ENC=0ec54e5f130a91c0f228d21795ede10b31c992a1f73e45dcd827e843a71a9b50
    DEC=5d1cfef155d8e07f213bd2f3229b5f6c5c29355c7fa5c1b9eb62cb1925725254
    JOIN=fd1d02f45c2ad3d6b67cc149811ad794ab4b020ed49a0a9e2790a8619d1cddd8
    TOK=17cc514451bcceac9c280068c71502f8448f99e9fb1456b8d0761651fd0392f2 ;;
  gigaam-v2-rnnt)
    REPO=csukuangfj/sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19; PUNCT=false
    ENC=b51efc61e3c0037ad1cb804079975468de3d175324fe8323aef5be4f5c6a38a1
    DEC=208e24cc150fb0ebca3fab169502796daa12e0255dcf7b4acf65015c436e9f76
    JOIN=4b02eced18e033fc5173e6c47b6ab166b5efea8d35c3f33a6755ff0d622fb5b0
    TOK=17cc514451bcceac9c280068c71502f8448f99e9fb1456b8d0761651fd0392f2 ;;
  *) echo "неизвестная ASR-модель: $ASR" >&2; exit 2 ;;
esac

case "$EMB" in
  eres2net)
    EMB_FILE=3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common.onnx
    EMB_SHA=e2d2048292e055f7b61cdec3db010503f35369b245bf0b3bbad021c9a91e4053 ;;
  campplus)
    EMB_FILE=3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx
    EMB_SHA=aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2 ;;
  titanet-small)
    EMB_FILE=nemo_en_titanet_small.onnx
    EMB_SHA=ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e ;;
  *) echo "неизвестная модель эмбеддингов: $EMB" >&2; exit 2 ;;
esac

rm -rf "$ASSETS"
A="asr/$ASR"
fetch "$HF/$REPO/resolve/main/encoder.int8.onnx" "$ENC" "$ASSETS/$A/encoder.int8.onnx"
fetch "$HF/$REPO/resolve/main/decoder.onnx" "$DEC" "$ASSETS/$A/decoder.onnx"
fetch "$HF/$REPO/resolve/main/joiner.onnx" "$JOIN" "$ASSETS/$A/joiner.onnx"
fetch "$HF/$REPO/resolve/main/tokens.txt" "$TOK" "$ASSETS/$A/tokens.txt"
fetch "$GH/asr-models/silero_vad.onnx" \
  9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6 "$ASSETS/vad/silero_vad.onnx"
fetch "$GH/speaker-recongition-models/$EMB_FILE" "$EMB_SHA" "$ASSETS/diarization/$EMB_FILE"

VERSION=$(printf '%s' "$ENC$DEC$JOIN$TOK$EMB_SHA$THRESHOLD" | shasum -a 256 | cut -c1-12)
cat > "$ASSETS/manifest.json" <<JSON
{
  "version": "$VERSION",
  "asr": {
    "id": "$ASR",
    "type": "nemo_transducer",
    "encoder": "$A/encoder.int8.onnx",
    "decoder": "$A/decoder.onnx",
    "joiner": "$A/joiner.onnx",
    "tokens": "$A/tokens.txt",
    "featureDim": 64,
    "punctuation": $PUNCT
  },
  "vad": "vad/silero_vad.onnx",
  "embedding": "diarization/$EMB_FILE",
  "clusterThreshold": $THRESHOLD
}
JSON
echo "модели в $ASSETS ($ASR, $EMB, порог $THRESHOLD)"
