// SPDX-License-Identifier: Apache-2.0
// Vexto bounded PCM16 frontend over the unmodified NeMo-Speech.cpp diarization C ABI.
#include "nemo_speech/diar.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

static uint16_t u16(const unsigned char* p) { return p[0] | (uint16_t(p[1]) << 8); }
static uint32_t u32(const unsigned char* p) { return u16(p) | (uint32_t(u16(p + 2)) << 16); }

static uint32_t open_pcm(std::ifstream& file) {
    unsigned char header[12];
    if (!file.read(reinterpret_cast<char*>(header), 12) || std::memcmp(header, "RIFF", 4) ||
        std::memcmp(header + 8, "WAVE", 4)) throw std::runtime_error("expected RIFF WAV");
    bool format_ok = false;
    while (true) {
        unsigned char chunk[8];
        if (!file.read(reinterpret_cast<char*>(chunk), 8)) throw std::runtime_error("missing WAV data");
        uint32_t size = u32(chunk + 4);
        if (!std::memcmp(chunk, "fmt ", 4)) {
            unsigned char format[16];
            if (size < 16 || !file.read(reinterpret_cast<char*>(format), 16)) throw std::runtime_error("bad WAV format");
            format_ok = u16(format) == 1 && u16(format + 2) == 1 && u32(format + 4) == 16000 &&
                        u16(format + 12) == 2 && u16(format + 14) == 16;
            file.seekg(size - 16 + (size & 1), std::ios::cur);
        } else if (!std::memcmp(chunk, "data", 4)) {
            if (!format_ok || size == 0 || (size & 1)) throw std::runtime_error("expected nonempty mono PCM16 16kHz");
            return size;
        } else {
            file.seekg(uint64_t(size) + (size & 1), std::ios::cur);
        }
        if (!file) throw std::runtime_error("truncated WAV chunk");
    }
}

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--help") {
        std::puts("Vexto Nemotron: MODEL PCM16_WAV --gpu -1 --preset v3-offline --rttm ID");
        return 0;
    }
    if (argc < 3) { std::fprintf(stderr, "usage: native MODEL WAV --gpu -1 --preset v3-offline --rttm ID\n"); return 1; }
    std::string recording = "recording";
    for (int i = 3; i < argc; i += 2) {
        if (i + 1 >= argc) return 1;
        std::string flag = argv[i], value = argv[i + 1];
        if (flag == "--rttm") recording = value;
        else if ((flag == "--gpu" && value == "-1") || (flag == "--preset" && value == "v3-offline")) {}
        else return 1;
    }
    if (recording.empty() || recording.find_first_of(" \t\r\n") != std::string::npos) return 1;
    nemo_speech_diar_model* model = nullptr;
    nemo_speech_diar_stream* stream = nullptr;
    try {
        std::ifstream input(argv[2], std::ios::binary);
        uint32_t remaining = open_pcm(input);
        nemo_speech_diar_model_config cfg = {};
        cfg.size = sizeof(cfg); cfg.model_path = argv[1]; cfg.gpu = -1; cfg.preset = "v3-offline";
        auto checked = [](nemo_speech_asr_status status) {
            if (status != NEMO_SPEECH_ASR_OK) throw std::runtime_error(nemo_speech_asr_last_error());
        };
        checked(nemo_speech_diar_create(&cfg, &model));
        checked(nemo_speech_diar_stream_open(model, &stream));
        // Same 160ms pushes as upstream example, without whole-WAV byte/float vectors.
        unsigned char pcm[5120];
        float samples[2560];
        while (remaining) {
            size_t bytes = std::min<size_t>(sizeof(pcm), remaining);
            if (!input.read(reinterpret_cast<char*>(pcm), bytes)) throw std::runtime_error("truncated PCM data");
            for (size_t i = 0; i < bytes / 2; ++i) samples[i] = int16_t(u16(pcm + 2 * i)) / 32768.0f;
            checked(nemo_speech_diar_stream_push_f32(stream, samples, bytes / 2, 16000));
            remaining -= bytes;
        }
        checked(nemo_speech_diar_stream_finish(stream));
        size_t count = 0;
        checked(nemo_speech_diar_segments(stream, nullptr, nullptr, 0, &count));
        std::vector<nemo_speech_diar_segment> segments(count);
        if (count) checked(nemo_speech_diar_segments(stream, nullptr, segments.data(), count, &count));
        for (const auto& row : segments) {
            if (!std::isfinite(row.start_time) || !std::isfinite(row.end_time) || row.start_time < 0 ||
                row.end_time <= row.start_time) throw std::runtime_error("invalid diarization interval");
            std::printf("SPEAKER %s 1 %.3f %.3f <NA> <NA> speaker_%d <NA> <NA>\n", recording.c_str(),
                        row.start_time, row.end_time - row.start_time, row.speaker);
        }
        nemo_speech_diar_stream_close(stream); nemo_speech_diar_destroy(model);
        return 0;
    } catch (const std::exception& error) {
        if (stream) nemo_speech_diar_stream_close(stream);
        if (model) nemo_speech_diar_destroy(model);
        std::fprintf(stderr, "Vexto Nemotron: %s\n", error.what());
        return 2;
    }
}
