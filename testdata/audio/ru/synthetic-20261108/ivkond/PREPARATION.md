# ivkond: five unique recordings prepared before inference

Dataset: `ivkond/synthetic-speech-diarization-ru`; revision `3fa8404cfde61d2e3a3369000ce771747821e844`; license MIT according to the dataset card.

Selection: first five unique source-float32 SHA256 waveforms after the original metadata-only filter (2–3 speakers, 16kHz, no noise/overlap/simultaneous flags, valid nonempty published text and nonoverlapping segments). Order: source shard filename, row group, row index. No duration or model-quality filtering.

An amendment was fixed before any inference: the initial first five matching rows [31,294,297,346,348] included duplicate source waveforms at294/297 and346/348. The initial selection/manifest/QA are retained as `initial-*`; original WAV/reference files remain present. `selection-rule.json` records the amendment, and `preselected.json` records all examined rows and duplicate rejection reasons.

| Row | WAV | Seconds | Speakers | Segments |
|---:|---|---:|---:|---:|
| 31 | `ivkond_train_000031.wav` | 35.411125 | 2 | 7 |
| 294 | `ivkond_train_000294.wav` | 44.821438 | 2 | 9 |
| 346 | `ivkond_train_000346.wav` | 45.559875 | 2 | 9 |
| 391 | `ivkond_train_000391.wav` | 43.801625 | 2 | 4 |
| 1064 | `ivkond_train_001064.wav` | 54.131937 | 2 | 8 |

Each `.reference.json` preserves all original published metadata/segments and a lossless app-contract view. `.reference.txt` concatenates published text in start order; RTTM uses `end-start`. References come from the dataset, not the tested ASR/LLM/VAD.

## Boundary limitations

- The published duration field disagrees with end-start in many segments. Original values are preserved; RTTM does not use that field.
- Published regions contain leading/internal quiet intervals; the longest observed leading interval below1e-4 amplitude is about3.7seconds. They are utterance/clip allocation spans rather than a verified human VAD segmentation. Use this fixture for WER only; DER is N/A because these spans are unsuitable as validated speech-only boundaries.
- Boundary diagnostics are descriptive, not reference correction. No segments or text were discarded for measured model quality.
- All five final source waveform SHA256 values differ. PCM conversion clipped no samples.
- Actual Parquet schema: audio struct(array:list<float32>,sampling_rate:int64); speaker segments list(struct(speaker_id:int64,start:float64,end:float64,duration:float64,text:string)). See `source-schema.txt` for complete schema. The loader script has a different Audio/audio_path declaration, so the actual Parquet schema was used.

The portable WAV/RTTM/reference JSON and hashes are committed next to this note; pinned source and selection rule are in ../manifest.json. Recompute scores with tools/benchmarks/score_synthetic_fixture.py as documented in the report.
