"""Cut a sherpa Whisper decoder down to spoken-language identification.

The encoder stays intact. The decoder keeps one start-of-transcript row for
input and one output row per language. Those rows are the original Whisper
vectors, so the language logits of a single decoder step stay the same.
The full text vocabulary is not needed and is not copied.

sherpa-onnx 2.0.1 reads ``sot``, ``all_language_tokens`` and
``all_language_codes`` from the encoder metadata and indexes the decoder
logits with those token ids. This script rewrites that metadata so token 0
is the start token and logit ``i`` is ``codes[i]``.
"""

import argparse
from pathlib import Path


def language_id_metadata(codes):
    """Metadata that makes sherpa treat logit i as codes[i]."""
    cleaned = []
    for code in codes:
        text = str(code).strip()
        if not text or any(ch.isspace() for ch in text) or "," in text:
            raise ValueError(f"invalid language code: {code!r}")
        cleaned.append(text)
    if len(set(cleaned)) != len(cleaned):
        raise ValueError("language codes must be unique")
    if not cleaned:
        raise ValueError("at least one language is required")
    count = len(cleaned)
    return {
        "n_vocab": str(count),
        "sot": "0",
        "all_language_tokens": ",".join(str(i) for i in range(count)),
        "all_language_codes": ",".join(cleaned),
    }


def validate_ids(row_count, token_ids, sot_id):
    if not 0 <= int(sot_id) < row_count:
        raise ValueError(f"sot id {sot_id} is outside 0..{row_count - 1}")
    ids = [int(token_id) for token_id in token_ids]
    if any(not 0 <= token_id < row_count for token_id in ids):
        raise ValueError("a language token id is outside the embedding")
    if len(set(ids)) != len(ids):
        raise ValueError("language token ids must be unique")
    if not ids:
        raise ValueError("at least one language token is required")
    return ids


def slice_token_rows(rows, token_ids, sot_id):
    """Return the start-token row and language rows in ``token_ids`` order."""
    ids = validate_ids(len(rows), token_ids, sot_id)
    return rows[int(sot_id)], [rows[token_id] for token_id in ids]


def _metadata_map(model):
    return {item.key: item.value for item in model.metadata_props}


def _set_metadata(model, updates):
    current = _metadata_map(model)
    current.update(updates)
    del model.metadata_props[:]
    for key, value in current.items():
        prop = model.metadata_props.add()
        prop.key = key
        prop.value = value


def _parse_whisper_languages(encoder):
    meta = _metadata_map(encoder)
    try:
        codes = [part.strip() for part in meta["all_language_codes"].split(",")]
        token_ids = [int(part) for part in meta["all_language_tokens"].split(",")]
        sot_id = int(meta["sot"])
    except KeyError as error:
        raise ValueError("encoder metadata is missing Whisper language fields") from error
    if len(codes) != len(token_ids):
        raise ValueError("language codes and token ids have different lengths")
    language_id_metadata(codes)
    return codes, token_ids, sot_id


def prune_whisper_lid(encoder_path, decoder_path, out_dir):
    """Write a language-only Whisper pair and return its file sizes."""
    import numpy as np
    import onnx
    from onnx import numpy_helper

    encoder = onnx.load(str(encoder_path), load_external_data=False)
    decoder = onnx.load(str(decoder_path), load_external_data=False)
    codes, token_ids, sot_id = _parse_whisper_languages(encoder)
    embedding = None
    kept = []
    for initializer in decoder.graph.initializer:
        if initializer.name == "textDecoder.token_embedding.weight":
            embedding = numpy_helper.to_array(initializer)
        else:
            kept.append(initializer)
    if embedding is None:
        raise ValueError("decoder has no textDecoder.token_embedding.weight")
    if embedding.ndim != 2:
        raise ValueError("token embedding must be a matrix")
    ids = validate_ids(embedding.shape[0], token_ids, sot_id)
    sot_row = np.ascontiguousarray(embedding[sot_id:sot_id + 1])
    language_rows = np.ascontiguousarray(embedding[ids])

    del decoder.graph.initializer[:]
    decoder.graph.initializer.extend(kept)
    decoder.graph.initializer.append(numpy_helper.from_array(sot_row, "lid_sot_embedding"))
    decoder.graph.initializer.append(
        numpy_helper.from_array(language_rows, "lid_language_embedding"))

    rewired = {"gather": 0, "logits": 0}
    for node in decoder.graph.node:
        if node.op_type == "Gather" and node.input and node.input[0] == "textDecoder.token_embedding.weight":
            node.input[0] = "lid_sot_embedding"
            rewired["gather"] += 1
        if node.op_type == "Identity" and node.input and node.input[0] == "textDecoder.token_embedding.weight":
            node.input[0] = "lid_language_embedding"
            rewired["logits"] += 1
    if rewired != {"gather": 1, "logits": 1}:
        raise ValueError(f"unexpected embedding uses: {rewired}")

    vocab = len(codes)
    for value in list(decoder.graph.output) + list(decoder.graph.value_info):
        tensor = value.type.tensor_type
        if not tensor.HasField("shape"):
            continue
        for dim in tensor.shape.dim:
            if dim.dim_value == int(embedding.shape[0]):
                dim.dim_value = vocab
                dim.dim_param = ""

    _set_metadata(encoder, language_id_metadata(codes))
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    encoder_out = out / "whisper-tiny-lid-encoder.int8.onnx"
    decoder_out = out / "whisper-tiny-lid-decoder.int8.onnx"
    labels_out = out / "languages.txt"
    onnx.save(encoder, str(encoder_out))
    onnx.save(decoder, str(decoder_out))
    labels_out.write_text("".join(f"{code}\n" for code in codes), encoding="utf-8")
    return {
        "languages": vocab,
        "encoder_bytes": encoder_out.stat().st_size,
        "decoder_bytes": decoder_out.stat().st_size,
        "encoder": str(encoder_out),
        "decoder": str(decoder_out),
        "labels": str(labels_out),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--encoder", type=Path, required=True)
    parser.add_argument("--decoder", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    result = prune_whisper_lid(args.encoder, args.decoder, args.out_dir)
    print(
        f"languages={result['languages']} "
        f"encoder_bytes={result['encoder_bytes']} "
        f"decoder_bytes={result['decoder_bytes']}"
    )


if __name__ == "__main__":
    main()
