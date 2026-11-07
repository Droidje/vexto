# Natural overlap v1

`overlap-head.json` stores the trained 3073-parameter binary classifier and its
3072-dimensional feature normalization. It contains no audio or transcript text.
Frozen encoder: official TorchAudio WavLM Base+ (MIT).
Training: 214 natural AMI windows; checkpoint selected before calibration/test.
Threshold, source checkpoint hash, validation and limitations are documented in
`docs/research/android-overlap.md`. Do not recalibrate on the disclosed test set.

`scripts/export_overlap_model.py` combines these weights with the SHA-verified
official encoder. Float32 export intentionally preserves the tested operating
point; quantization requires fresh parity and quality validation.
