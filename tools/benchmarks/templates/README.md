# Local keyword metadata

The LLM adds a separate `keywords` object. It never replaces the raw ASR text,
speaker IDs, timestamps, or required transcript export. Completing inference
does not establish valid metadata: the response still needs validation.

Replace `{{RAW_TRANSCRIPT}}` in `metadata-keywords.prompt.txt` with the exact ASR
`text` locally, then pass that plain user message to the runtime. Keep recordings,
rendered prompts and model outputs under an ignored work directory. Select input
chunks and output limits that fit the chosen context; these templates do not
tokenize or truncate long transcripts.

For Qwen3 with llama.cpp, use `--jinja --chat-template-file` pointing to
`qwen3-nonthinking.jinja`; the template unconditionally closes the empty thinking
prefill. It is intended for one request with string system/user messages and no
tools or assistant history. `--json-schema-file metadata-keywords.schema.json`
constrains the output shape, but cannot guarantee literal phrases or relevance.

Run the portable filter (Python 3.10+, standard library only):

```shell
python tools/benchmarks/filter_metadata.py --asr-json asr.json --model-output llm.stdout.txt --output metadata.json
```

The input ASR JSON must contain a string `text`; other ASR fields are ignored.
The model output file must contain one JSON object with only a `keywords` array.
JSON whitespace and one trailing `[end of text]` runtime marker are accepted;
thinking, fences, prefixes, additional JSON and other suffixes reject the response.

The output contains at most three literal phrases, preserving case and internal
whitespace. Outer metadata whitespace is stripped and audited. Each phrase must
contain one to five whitespace-separated units with Unicode letters or numbers.
Unicode letters, numbers, combining marks and connector punctuation protect word
boundaries. Empty, duplicate, nonliteral and excessive phrases are discarded.
This checks extraction constraints, not whether the keywords are useful.

`metadata.audit.json` records input hashes, rejected candidate indices and reasons,
normalizations and `model_output_fully_valid`. Use `--audit path.json` to choose
another sidecar. Individual rejected phrases produce status `filtered` and exit 0;
an invalid whole response produces empty keywords, status `rejected` and exit 2.
Do not report a filtered model response as fully valid. Input files are read only;
output paths that alias either input or each other are rejected.

Run all benchmark tests, including these synthetic metadata cases:

```shell
python -m unittest discover -s tools/benchmarks/tests -p "test_*.py" -v
```

## Candidate ID selection on Android

`run_quality_pipeline.py --metadata-mode candidates` uses a selection-only mode.
The default `literal` mode remains available to reproduce the original free-text
keyword experiment. Candidate selection does not change the primary ASR text,
speaker IDs, timestamps or transcript export.

Compile both `android/VextoPostprocess.java` and `android/CandidateKeywords.java`
with UTF-8 source encoding, then include all generated classes in the DEX. Stage
`candidate-keywords.prompt.txt` and `candidate-keywords.schema.json` on Android
under `pipeline2/llm/`, alongside the existing nonthinking chat template.

On Android, `prompt-candidates ASR_STDOUT TEMPLATE OUT_PROMPT OUT_POOL` builds up
to 24 unique literal phrases of 2–4 whitespace-delimited words. It samples at most
192 word positions over the whole transcript, excludes punctuation-only units
and generic numbered speaker-label prefixes, and deduplicates exact strings.
Windows longer than 160 UTF-16 code units are skipped. This is a bounded n-gram
heuristic, not semantic keyword extraction or a guarantee of useful candidates.

The pool is an object containing `raw_asr_sha256` and a `candidates` array of
`{"id":1,"text":"..."}` objects. IDs are deterministic for the same ASR file.
The fresh prompt substitutes `{{RAW_TRANSCRIPT}}` and `{{CANDIDATES_JSON}}`
only in the template; inserted transcript contents are not interpreted as further
placeholders. The model must return only `{"candidate_ids":[1]}`, selecting at
most three unique IDs that exist in the pool. An empty selection is allowed.

`metadata-candidates ASR_STDOUT POOL LLM_STDOUT OUT_JSON OUT_AUDIT` verifies the
pool's source hash and every phrase against the current raw transcript. Valid
selected IDs are mapped back to the exact stored phrases. Free-generated text is
never copied into `keywords`. Invalid IDs and duplicates are dropped with audit
reasons; malformed model output yields empty keywords and a parse-error audit.
A stale or invalid pool fails the action rather than reusing another transcript.
The audit marks `selection_only=true` and `raw_asr_rewritten=false`.

Candidate mode uses temperature 0 and a 64-token output limit. The context remains
1024 tokens, so longer inputs still need a separate chunking/context strategy.
`--prompt-template` and `--metadata-schema` can override the mode-specific paths.
All pools, rendered prompts, model outputs and audits contain private recording
content and belong in ignored local output directories.

## Optional ASR disagreement and punctuation review

The quick runner now defaults to `--metadata-mode none`; historical keyword modes
remain explicit opt-ins. This removes the keyword-generation delay from the main
ASR/export path. It does not replace the product requirement for a local LLM:
the new role is a separate, bounded ASR review with suggestions and punctuation.

Prepare short diagnostic prompts from two complete saved ASR outputs:

```text
python tools/benchmarks/review_asr.py prepare --primary asr.log --secondary whisper.json --output-dir review-local --fragment-words 20 --max-fragments 1
```

The host utility locates disagreements and writes a context and prompt per bounded
fragment. It performs no inference and never rewrites either full transcript.
Prompts contain at most 50 words from each ASR; the default maximum is three
fragments, so the review is intentionally not exhaustive. Run one generated prompt
and `asr-review.schema.json` with the local Android LLM as a separate optional task.
The second ASR is an alternative, not ground truth.

```text
python tools/benchmarks/review_asr.py validate --context review-local/fragment-1.context.json --model-output review.stdout.txt --output review-local/review.json
```

The result contains proposed local replacements and a separate punctuation variant.
No lexical proposal is applied automatically. Punctuation is accepted only when
all Unicode words, case, protected non-punctuation characters and word order are
preserved. Original ASR remains read-only; rejected punctuation and invalid or
nonliteral proposals receive audit reasons. A valid suggestion is not proof of
what the audio contains. Review failures do not invalidate the main RAW export.

## Denoiser timing

`run_quality_pipeline.py` enables GTCRN by default (`--no-denoise` disables it).
Diarization uses the untouched original; both ASR passes use a separate filtered
WAV. Android `align-denoised` requires PCM16 mono 16 kHz and restores the original
sample count by tail padding or truncation. Its sidecar explicitly records
`alignment_verified=false`: no undocumented algorithmic delay is guessed, and
restoring duration is not sample-accurate waveform alignment. Timing after the
filter retains this uncertainty. Compile `WaveLength.java` with the other Java
sources and include every generated class in the DEX.
