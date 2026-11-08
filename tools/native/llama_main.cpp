// SPDX-License-Identifier: Apache-2.0
// A model/context is retained; KV memory and grammar/sampler are reset per request.
#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "json-schema-to-grammar.h"
#include "worker_mailbox.h"
#include <memory>

namespace {
std::string chat_data(std::string value) {
    // Transcript text is data. Escape the marker prefix before chat tokenization.
    for (size_t at = 0; (at = value.find("<|", at)) != std::string::npos; ++at)
        value[at] = '[';
    return value;
}

void trim_partial_utf8(std::string & text) {
    if (text.empty()) return;
    size_t start = text.size() - 1;
    while (start > 0 && (static_cast<unsigned char>(text[start]) & 0xc0) == 0x80) --start;
    const auto byte = static_cast<unsigned char>(text[start]);
    const size_t needed = byte < 0x80 ? 1 : byte < 0xe0 ? 2 : byte < 0xf0 ? 3 : 4;
    if (text.size() - start < needed) text.resize(start);
}

std::vector<size_t> prefix_boundaries(const std::string & text, bool words) {
    std::vector<size_t> result;
    for (size_t at = 0; at < text.size();) {
        const auto first = static_cast<unsigned char>(text[at++]);
        uint32_t cp = first;
        int extra = 0;
        if (first >= 0xc2 && first <= 0xdf) { cp = first & 0x1f; extra = 1; }
        else if (first >= 0xe0 && first <= 0xef) { cp = first & 0x0f; extra = 2; }
        else if (first >= 0xf0 && first <= 0xf4) { cp = first & 7; extra = 3; }
        else if (first >= 0x80) throw std::runtime_error("Invalid UTF-8 planning input");
        for (int i = 0; i < extra; ++i) {
            if (at == text.size() || (static_cast<unsigned char>(text[at]) & 0xc0) != 0x80)
                throw std::runtime_error("Invalid UTF-8 planning input");
            cp = (cp << 6) | (static_cast<unsigned char>(text[at++]) & 0x3f);
        }
        if ((extra == 1 && cp < 0x80) || (extra == 2 && cp < 0x800) ||
            (extra == 3 && cp < 0x10000) || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff))
            throw std::runtime_error("Invalid UTF-8 planning input");
        const bool space = (cp >= 9 && cp <= 13) || cp == 0x20 || cp == 0x85 || cp == 0xa0 ||
            cp == 0x1680 || (cp >= 0x2000 && cp <= 0x200a) || cp == 0x2028 || cp == 0x2029 ||
            cp == 0x202f || cp == 0x205f || cp == 0x3000;
        if (!words || space || at == text.size()) result.push_back(at);
    }
    return result;
}
}

int main(int argc, char ** argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--help") {
            std::puts("Vexto Qwen vexto-qwen-protocol=2 -m MODEL -f PROMPT (--json-schema-file SCHEMA | --summary-system-file SYSTEM) [--worker-dir DIR] [-t 2|4]");
            return 0;
        }
        const bool worker = argc >= 5 && std::string(argv[1]) == "--worker-dir";
        const std::string directory = worker ? argv[2] : "";
        const std::vector<std::string> initial(argv + (worker ? 3 : 1), argv + argc);
        const auto model_path = vexto::option(initial, "-m");
        const auto summary_path = vexto::option(initial, "--summary-system-file");
        const bool summary = !summary_path.empty();
        llama_backend_init();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0; mp.use_extra_bufts = false;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(model_path.c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error("Cannot initialize packaged Qwen model");
        auto cp = llama_context_default_params();
        cp.n_ctx = std::stoi(vexto::option(initial, "-c", "1024"));
        cp.n_batch = std::stoi(vexto::option(initial, "-b", "128"));
        cp.n_ubatch = std::stoi(vexto::option(initial, "-ub", "64"));
        cp.n_threads = std::stoi(vexto::option(initial, "-t", "2"));
        cp.n_threads_batch = std::stoi(vexto::option(initial, "--threads-batch", "2"));
        cp.type_k = GGML_TYPE_F16; cp.type_v = GGML_TYPE_F16;
        if (cp.n_ctx != (summary ? 2048 : 1024) || cp.n_batch != 128 || cp.n_ubatch != 64 ||
            (cp.n_threads != 2 && cp.n_threads != 4) || cp.n_threads_batch != cp.n_threads)
            throw std::runtime_error("Unsupported bounded Qwen configuration");
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("Cannot initialize Qwen context");
        const auto * vocab = llama_model_get_vocab(model.get());
        auto generate = [&](const std::vector<std::string> & args) {
            if (vexto::option(args, "-m") != model_path) throw std::runtime_error("Worker model changed");
            if (vexto::option(args, "--summary-system-file") != summary_path)
                throw std::runtime_error("Worker generation mode changed");
            // Exact expansion of SelectorPrompt.NonThinkingTemplate for its single user message.
            const auto prompt = vexto::read_file(vexto::option(args, "-f"), summary ? 32768 : 8192);
            const auto system = summary
                ? "<|im_start|>system\n" + vexto::read_file(summary_path, 400) + "<|im_end|>\n" : "";
            const auto format = [&](const std::string & input) {
                return system + "<|im_start|>user\n" + chat_data(input) +
                    "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
            };
            const int max_tokens = std::stoi(vexto::option(args, "-n", "16"));
            if (max_tokens < 1 || max_tokens > (summary ? 192 : 16))
                throw std::runtime_error("Qwen output token budget exceeded");
            if (vexto::option(args, "--fit-prefix") == "1") {
                if (!summary) throw std::runtime_error("Token planning requires summary mode");
                const auto suffix = vexto::read_file(vexto::option(args, "--summary-suffix-file"), 1024);
                // Token counts are not monotonic in prefix length. Inspect boundaries from
                // longest to shortest instead of assuming a binary-search predicate.
                for (bool words : {true, false}) {
                    const auto boundaries = prefix_boundaries(prompt, words);
                    for (auto it = boundaries.rbegin(); it != boundaries.rend(); ++it) {
                        const auto candidate = format(prompt.substr(0, *it) + suffix);
                        const int count = -llama_tokenize(vocab, candidate.data(), int(candidate.size()), nullptr, 0, true, true);
                        if (count > 0 && count + max_tokens <= int(cp.n_ctx)) return vexto::json{
                            {"prefix_bytes", *it}, {"prompt_tokens", count}, {"context_tokens", cp.n_ctx},
                            {"max_output_tokens", max_tokens}, {"accepted", true}}.dump();
                    }
                }
                throw std::runtime_error("QWEN_CONTEXT_BUDGET_EXCEEDED");
            }
            const auto formatted = format(prompt);
            int count = -llama_tokenize(vocab, formatted.data(), int(formatted.size()), nullptr, 0, true, true);
            const auto budget = vexto::json{
                {"prompt_tokens", count}, {"context_tokens", cp.n_ctx},
                {"max_output_tokens", max_tokens},
                {"accepted", count > 0 && count + max_tokens <= int(cp.n_ctx)}};
            const auto audit = vexto::option(args, "--prompt-audit-file");
            if (!audit.empty()) vexto::write_file(audit, vexto::json{
                {"prompt_utf8_bytes", prompt.size()}, {"prompt_tokens", count}, {"context_tokens", cp.n_ctx},
                {"max_output_tokens", max_tokens}, {"threads", cp.n_threads},
                {"accepted", count > 0 && count + max_tokens <= int(cp.n_ctx)}}.dump());
            // Planning uses the exact same escaped ChatML and vocabulary as generation.
            // It does not evaluate the prompt or alter the retained context/KV state.
            if (vexto::option(args, "--count-tokens") == "1") {
                if (!summary) throw std::runtime_error("Token planning requires summary mode");
                return budget.dump();
            }
            if (count <= 0 || count + max_tokens > int(cp.n_ctx)) throw std::runtime_error("QWEN_CONTEXT_BUDGET_EXCEEDED");
            std::vector<llama_token> tokens(count);
            if (llama_tokenize(vocab, formatted.data(), int(formatted.size()), tokens.data(), count, true, true) != count)
                throw std::runtime_error("Qwen tokenization failed");
            llama_memory_clear(llama_get_memory(context.get()), true);
            common_params_sampling sampling;
            sampling.temp = 0; sampling.seed = 42; sampling.penalty_repeat = 1.0f;
            if (!summary) {
                const auto schema = common_json::parse(vexto::read_file(vexto::option(args, "--json-schema-file"), 8192));
                sampling.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, json_schema_to_grammar(schema));
            }
            common_sampler_ptr sampler(common_sampler_init(model.get(), sampling));
            if (!sampler) throw std::runtime_error("Cannot initialize constrained Qwen sampler");
            for (size_t at = 0; at < tokens.size(); at += cp.n_batch) {
                auto batch = llama_batch_get_one(tokens.data() + at, int(std::min<size_t>(cp.n_batch, tokens.size() - at)));
                if (llama_decode(context.get(), batch)) throw std::runtime_error("Qwen prompt evaluation failed");
            }
            std::string response;
            for (int i = 0; i < max_tokens; ++i) {
                auto token = common_sampler_sample(sampler.get(), context.get(), -1);
                if (llama_vocab_is_eog(vocab, token)) break;
                common_sampler_accept(sampler.get(), token, true);
                char text[256];
                const int length = llama_token_to_piece(vocab, token, text, sizeof(text), 0, true);
                if (length < 0) throw std::runtime_error("Qwen token output exceeds bound");
                if (summary && response.size() + size_t(length) > 640) break;
                response.append(text, size_t(length));
                auto batch = llama_batch_get_one(&token, 1);
                if (llama_decode(context.get(), batch)) throw std::runtime_error("Qwen output evaluation failed");
            }
            if (summary) trim_partial_utf8(response);
            return response;
        };
        if (worker) vexto::serve(directory, generate);
        else {
            const auto output = generate(initial);
            if (std::fwrite(output.data(), 1, output.size(), stdout) != output.size())
                throw std::runtime_error("Cannot write selector response");
        }
    } catch (const std::exception & error) {
        std::fprintf(stderr, "Vexto Qwen worker: %s\n", error.what());
        return 1;
    }
}
