---
license: mit
task_categories:
- automatic-speech-recognition
- audio-classification
tags:
- audio
- speech
- diarization
- synthetic
language:
- ru
size_categories:
- 1K<n<10K
---

# synthetic-speech-diarization-ru

Synthetic speech diarization dataset in Parquet format.

## Dataset Details

- **Number of tracks**: 2000
- **Sampling rate**: 16000 Hz
- **Audio format**: Embedded in Parquet files (Audio feature compatible)
- **Storage**: Parquet format for efficient loading

## Dataset Structure

The dataset contains audio tracks with speaker diarization annotations, stored directly in Parquet format.

### Features

- `audio`: Audio waveform (Audio feature with array and sampling_rate)
- `duration`: Track duration in seconds
- `num_speakers`: Number of speakers in the track
- `speakers`: List of speaker segments with timestamps and text
- `speaker_volumes`: Speaker volume levels
- `conversation_type`: Type of conversation (dialogue, monologue, etc.)
- `difficulty`: Difficulty level (easy, medium, hard)
- `has_overlaps`: Whether track contains overlapping speech
- `has_simultaneous`: Whether track contains simultaneous speech
- `has_noise`: Whether track contains background noise

## Usage

```python
from datasets import load_dataset

# Load dataset from HuggingFace Hub directly
dataset = load_dataset("ivkond/synthetic-speech-diarization-ru")

# Access a sample
sample = dataset[0]
print(f"Duration: {sample['duration']}s")
print(f"Speakers: {sample['num_speakers']}")
```

## Notes

- Audio data is embedded directly in Parquet files for efficient streaming
- No custom loading script required
- Compatible with standard Hugging Face dataset operations