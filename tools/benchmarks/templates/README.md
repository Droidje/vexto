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
