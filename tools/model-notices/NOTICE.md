# Bundled model notices

The exact download URLs, byte sizes, hashes and declared licenses are in
`assets/quality/manifest.json` in the base APK.

- GigaAM v3 RNNT and GigaAM-Emo originate from [GigaAM by SberDevices / Salute](https://github.com/salute-developers/GigaAM). This distribution includes the manifest-pinned ONNX exports by csukuangfj and vvpreo.
- GTCRN originates from [Xiaobin Rong and the GTCRN contributors](https://github.com/Xiaobin-Rong/gtcrn), distributed here as the manifest-pinned Sherpa ONNX export.
- Nemotron 3 Diarization originates from [NVIDIA Corporation](https://huggingface.co/nvidia/Nemotron-3-Diarization). Vexto converted the checkpoint to F32 GGUF. The OpenMDW 1.1 license covers the NVIDIA model and the converted copy. See the [conversion recipe and provenance](https://github.com/Droidje/vexto/blob/main/docs/research/nemotron-android.md).
- Whisper Large v3 originates from [OpenAI](https://github.com/openai/whisper). This distribution includes the Q5_0 quantization by [whisper.cpp contributors](https://huggingface.co/ggerganov/whisper.cpp). The whisper.cpp license is bundled with the base APK's native runtime notices.
- Qwen3-1.7B originates from [Qwen / Alibaba Cloud](https://huggingface.co/Qwen/Qwen3-1.7B). This distribution includes the Q4_K_M GGUF quantization by [ggml-org](https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF).
- ERes2Net originates from [3D-Speaker / ModelScope](https://github.com/modelscope/3D-Speaker), distributed here as the manifest-pinned Sherpa ONNX export.
