# GigaAM-Emo Android benchmark adapter

This standalone Java launcher consumes a **16 kHz, mono, PCM16 RIFF WAV**
recording (over 200 samples, at most 30 minutes). It implements the audio frontend,
runs ONNX Runtime CPU, and writes `angry`, `sad`, `neutral`, `positive` probabilities,
the largest-score label and its score (`confidence`). These are model scores,
not verified emotion labels or calibrated confidence. It selects short utterances;
the model does not separate speakers or overlapping voices.

The official model is **GigaAM-Emo (v1)**, even though the available third-party
export is hosted in a repository named `gigaam-v3-onnx`.

## Pinned contract and provenance

- [Official model/export implementation](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/gigaam/model.py)
  and [frontend](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/gigaam/preprocess.py),
  [MIT license](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/LICENSE).
- [Third-party export card](https://huggingface.co/vvpreo/gigaam-v3-onnx/blob/03cf125f754af526260037bc96f4edf0642e963b/README.md),
  [config and class order](https://huggingface.co/vvpreo/gigaam-v3-onnx/blob/03cf125f754af526260037bc96f4edf0642e963b/emo.yaml).
- [INT8 ONNX download](https://huggingface.co/vvpreo/gigaam-v3-onnx/resolve/03cf125f754af526260037bc96f4edf0642e963b/emo.int8.onnx):
  305,928,068 bytes; SHA256 `5639b328486d5af9c1a7aff2c584df8319250034310c5a4b7ff4eae95d555b02`.
  This is a third-party dynamic quantization, not an officially validated mobile export.

Graph inputs are `features: float32 [1,64,T]`, `feature_lengths: int64 [1]`;
output is `probs: float32 [1,4]`. Features are mel-major; `T=floor(samples/160)+1`.
The frontend follows Torchaudio MelSpectrogram defaults used by the official code:
periodic Hann 400, FFT400, hop160, centered reflection padding200, magnitude squared,
64 HTK triangular mel bands over 0–8000 Hz, no normalization, then natural log after
clamping to `[1e-9,1e9]`. No preemphasis, dither, DC removal or feature normalization.
Batch size is deliberately one: exported temporal pooling is not padding-aware.

Sherpa-ONNX 2.0.1's [audio-tagging factory](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/csrc/audio-tagging-impl.cc)
only dispatches CED/Zipformer. Its CED tensor and frontend contract differs;
renaming the Emo model does not make it compatible. This launcher uses ORT directly.

## Build and run

Build with Python 3.11+, JDK11+ and Android SDK platform36/build-tools36.1.0
(no ADB or inference in this script):

```text
python tools/benchmarks/android/emo/build_emo.py --sdk ANDROID_SDK --java-home JDK --work-dir IGNORED_BUILD_DIR
```

The script verifies Maven's pinned ORT1.28.0 AAR SHA256, compiles the launcher,
DEXes it with ORT Java classes, and extracts its matching x86_64 native libraries.
All dependencies and outputs remain in the requested work directory.
Do not mix these JNI/native libraries with another ORT release in one process.

After separately copying `gigaemo.jar`, both `.so` files and the ONNX model onto
the device, run in a fresh process (replace `/data/local/tmp/emo` with that directory):

```sh
EMO=/data/local/tmp/emo
CLASSPATH="$EMO/gigaemo.jar" LD_LIBRARY_PATH="$EMO" \
  app_process -Djava.library.path="$EMO" / GigaEmo \
  "$EMO/emo.int8.onnx" "$EMO/recording.wav" "$EMO/scores.json" "$EMO/diarization.txt"
```

Stable positional CLI: `GigaEmo MODEL WAV OUT_JSON [DIAR_LOG]` (two CPU threads).
Flags are also supported: `--model MODEL --wav WAV --output OUT_JSON
--diarization DIAR_LOG --threads 2`. RTTM or Sherpa `start -- end speaker_00`
intervals select the first nonoverlapping continuous 2–8-second portion for each
of at most eight speakers. Speaker IDs are one-based by first acoustic encounter,
independent of ASR. Portions overlapping another diarized speaker are excluded;
speakers with no eligible portion are omitted. Diarization errors can still select
the wrong speaker. The same ORT session is reused for all selected portions.
Without `DIAR_LOG`, the first eight seconds are explicitly marked as aggregate
and `speaker_id` is null. Output has `segments` with times, scores and labels, and
`source: model_prediction_not_true_emotion`.

`--frontend-only --features-out mel.f32` skips model loading/inference and dumps
mel-major little-endian float32 features for parity checks. On a host JVM, use
`java -cp "gigaemo-classes.jar;ort/classes.jar" GigaEmo` with those arguments
(use `:` as the classpath separator on Linux/macOS).

Host frontend parity against Torchaudio2.9.1 passed on four synthetic PCM fixtures,
including silence and an odd sample length: maximum absolute log-mel difference
`4.49e-5`. This check runs no acoustic model. The standalone Java and DEX build
also passed. A measured Android x86_64/API36 run on the public poem completed:
native process wall4.106151s, native MaxRSS1,187,968KiB,
conservative observed peak1,225,568,256bytes, with128MiB reserve1,359,785,984bytes.
One nonoverlapping eight-second utterance was evaluated using two CPU threads.
Android execution is verified for this configuration; emotion accuracy and
confidence calibration remain unvalidated.

Build/feature parity checks alone do not establish model accuracy or memory use
on other recordings/devices. The measured optional stage stayed within the strict
2,000,000,000-byte process budget, including the128MiB reserve, after releasing
ASR/diarization models. Model file size is not process memory. A single utterance
prediction is not a whole-conversation mood or a person's true feeling.
