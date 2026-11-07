// SPDX-License-Identifier: Apache-2.0
// A model/context is retained; KV memory and grammar/sampler are reset per request.
#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "json-schema-to-grammar.h"
#include "worker_mailbox.h"
#include <memory>

int main(int argc, char ** argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--help") {
            std::puts("Vexto Qwen -m MODEL -f PROMPT --json-schema-file SCHEMA [--worker-dir DIR]");
            return 0;
        }
        const bool worker = argc >= 5 && std::string(argv[1]) == "--worker-dir";
        const std::string directory = worker ? argv[2] : "";
        const std::vector<std::string> initial(argv + (worker ? 3 : 1), argv + argc);
        const auto model_path = vexto::option(initial, "-m");
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
        if (cp.n_ctx != 1024 || cp.n_batch != 128 || cp.n_ubatch != 64 || cp.n_threads != 2 || cp.n_threads_batch != 2)
            throw std::runtime_error("Unsupported bounded Qwen configuration");
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("Cannot initialize Qwen context");
        const auto * vocab = llama_model_get_vocab(model.get());
        auto generate = [&](const std::vector<std::string> & args) {
            if (vexto::option(args, "-m") != model_path) throw std::runtime_error("Worker model changed");
            // Exact expansion of SelectorPrompt.NonThinkingTemplate for its single user message.
            const auto prompt = vexto::read_file(vexto::option(args, "-f"), 864);
            const auto formatted = "<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
            const auto schema = common_json::parse(vexto::read_file(vexto::option(args, "--json-schema-file"), 8192));
            const int max_tokens = std::stoi(vexto::option(args, "-n", "16"));
            if (max_tokens < 1 || max_tokens > 16) throw std::runtime_error("Qwen output token budget exceeded");
            int count = -llama_tokenize(vocab, formatted.data(), int(formatted.size()), nullptr, 0, true, true);
            if (count <= 0 || count + max_tokens > int(cp.n_ctx)) throw std::runtime_error("Qwen context budget exceeded");
            std::vector<llama_token> tokens(count);
            if (llama_tokenize(vocab, formatted.data(), int(formatted.size()), tokens.data(), count, true, true) != count)
                throw std::runtime_error("Qwen tokenization failed");
            llama_memory_clear(llama_get_memory(context.get()), true);
            common_params_sampling sampling;
            sampling.temp = 0; sampling.seed = 42; sampling.penalty_repeat = 1.0f;
            sampling.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, json_schema_to_grammar(schema));
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
                response.append(text, size_t(length));
                auto batch = llama_batch_get_one(&token, 1);
                if (llama_decode(context.get(), batch)) throw std::runtime_error("Qwen output evaluation failed");
            }
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
