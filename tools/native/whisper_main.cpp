// SPDX-License-Identifier: Apache-2.0
// Persistent bounded PCM16 worker using the pinned whisper.cpp public API.
#include "whisper.h"
#include "worker_mailbox.h"
#include <cstdint>
#include <cstring>
#include <memory>

static uint16_t u16(const unsigned char * p) { return p[0] | uint16_t(p[1]) << 8; }
static uint32_t u32(const unsigned char * p) { return u16(p) | uint32_t(u16(p + 2)) << 16; }

static std::string byte_hex(const char * text) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string result;
    for (const auto * p = reinterpret_cast<const unsigned char *>(text); *p; ++p) {
        result.push_back(digits[*p >> 4]); result.push_back(digits[*p & 15]);
    }
    return result;
}

static std::vector<float> read_pcm(const std::string & path) {
    const auto bytes = vexto::read_file(path, 1 << 20);
    const auto * p = reinterpret_cast<const unsigned char *>(bytes.data());
    if (bytes.size() < 12 || std::memcmp(p, "RIFF", 4) || std::memcmp(p + 8, "WAVE", 4))
        throw std::runtime_error("Expected RIFF PCM16 WAV");
    bool valid = false;
    for (size_t at = 12; at + 8 <= bytes.size();) {
        const uint32_t size = u32(p + at + 4);
        if (uint64_t(at) + 8 + size > bytes.size()) throw std::runtime_error("Truncated WAV");
        const auto * data = p + at + 8;
        if (!std::memcmp(p + at, "fmt ", 4)) {
            valid = size >= 16 && u16(data) == 1 && u16(data + 2) == 1 && u32(data + 4) == 16000 && u16(data + 14) == 16;
        } else if (!std::memcmp(p + at, "data", 4)) {
            if (!valid || !size || (size & 1) || size / 2 > 240000) throw std::runtime_error("Expected <=15s mono PCM16 16kHz");
            std::vector<float> pcm(size / 2);
            for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = int16_t(u16(data + 2 * i)) / 32768.0f;
            return pcm;
        }
        at += 8 + size + (size & 1);
    }
    throw std::runtime_error("Missing WAV samples");
}

int main(int argc, char ** argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--help") {
            std::puts("Vexto Whisper [-m MODEL -f PCM16_WAV -of PREFIX] [--worker-dir DIR] -t N -ac N -l LANG");
            return 0;
        }
        const bool worker = argc >= 5 && std::string(argv[1]) == "--worker-dir";
        const std::string directory = worker ? argv[2] : "";
        std::vector<std::string> initial(argv + (worker ? 3 : 1), argv + argc);
        const auto model = vexto::option(initial, "-m");
        auto cp = whisper_context_default_params();
        cp.use_gpu = false;
        cp.flash_attn = vexto::flag(initial, "-fa");
        std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
            whisper_init_from_file_with_params(model.c_str(), cp), whisper_free);
        if (!context) throw std::runtime_error("Cannot initialize packaged Whisper model");
        auto generate = [&](const std::vector<std::string> & args) {
            if (vexto::option(args, "-m") != model) throw std::runtime_error("Worker model changed");
            const auto pcm = read_pcm(vexto::option(args, "-f"));
            const int beams = std::stoi(vexto::option(args, "-bs", "1"));
            auto params = whisper_full_default_params(beams > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
            params.n_threads = std::stoi(vexto::option(args, "-t", "2"));
            params.audio_ctx = std::stoi(vexto::option(args, "-ac", "512"));
            if (params.n_threads < 1 || params.n_threads > 8 || params.audio_ctx < 1 || params.audio_ctx > 1500 ||
                pcm.size() > size_t(params.audio_ctx) * 320) throw std::runtime_error("Invalid Whisper worker geometry");
            const auto language = vexto::option(args, "-l", "ru");
            params.language = language.c_str();
            params.no_context = true;
            params.token_timestamps = true;
            params.print_realtime = false; params.print_progress = false;
            params.print_timestamps = false; params.print_special = false;
            params.greedy.best_of = std::stoi(vexto::option(args, "-bo", "1"));
            // FAST performs one greedy decode instead of hidden temperature retries.
            if (vexto::flag(args, "--no-temperature-fallback")) params.temperature_inc = 0.0f;
            params.beam_search.beam_size = beams;
            if (whisper_full(context.get(), params, pcm.data(), int(pcm.size())) != 0)
                throw std::runtime_error("Whisper inference failed");
            auto segments = vexto::json::array();
            for (int i = 0; i < whisper_full_n_segments(context.get()); ++i) {
                auto tokens = vexto::json::array();
                for (int j = 0; j < whisper_full_n_tokens(context.get(), i); ++j) {
                    const auto token = whisper_full_get_token_data(context.get(), i, j);
                    const auto * text = whisper_full_get_token_text(context.get(), i, j);
                    tokens.push_back({{"text", text}, {"text_bytes_hex", byte_hex(text)}, {"id", token.id},
                        {"offsets", {{"from", token.t0 * 10}, {"to", token.t1 * 10}}}});
                }
                const auto * text = whisper_full_get_segment_text(context.get(), i);
                segments.push_back({{"text", text}, {"text_bytes_hex", byte_hex(text)}, {"tokens", tokens}});
            }
            const auto prefix = vexto::option(args, "-of");
            if (prefix.empty()) throw std::runtime_error("Missing Whisper output prefix");
            // Byte-level tokens may contain a partial UTF-8 character. Preserve their
            // exact bytes separately while emitting valid JSON strings for the UI parser.
            const auto output = vexto::json{{"transcription", segments}}.dump(
                -1, ' ', false, vexto::json::error_handler_t::replace);
            if (output.size() > 4000000) throw std::runtime_error("Whisper JSON exceeds bound");
            vexto::write_file(prefix + ".json", output);
            return std::string();
        };
        if (worker) vexto::serve(directory, generate);
        else generate(initial);
    } catch (const std::exception & error) {
        std::fprintf(stderr, "Vexto Whisper worker: %s\n", error.what());
        return 1;
    }
}
