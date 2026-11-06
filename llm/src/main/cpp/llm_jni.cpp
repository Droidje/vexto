// Тонкий JNI-слой над llama.cpp: загрузка модели, генерация с потоком токенов,
// отмена и освобождение. Вся логика промптов живёт в Kotlin.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <fstream>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define LOG_TAG "VextoLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::string chat_template;
    std::atomic<bool> cancel{false};
};

std::set<std::string> cpu_flags() {
    std::ifstream in("/proc/cpuinfo");
    std::set<std::string> flags;
    std::string line;
    while (std::getline(in, line)) {
        // x86: "flags : ...", arm64: "Features : ..."
        if (line.rfind("flags", 0) != 0 && line.rfind("Features", 0) != 0) continue;
        auto colon = line.find(':');
        if (colon == std::string::npos) continue;
        std::istringstream words(line.substr(colon + 1));
        std::string w;
        while (words >> w) flags.insert(w);
        break;
    }
    return flags;
}

// Кандидаты от самого быстрого к базовому. Ядро Linux убирает avx/avx2 из
// /proc/cpuinfo, если ОС не сохраняет их регистры (XSAVE), поэтому этим флагам
// можно доверять и на гипервизоре.
std::vector<std::string> cpu_variants() {
    auto f = cpu_flags();
    auto has = [&](std::initializer_list<const char *> names) {
        for (auto n : names) if (!f.count(n)) return false;
        return true;
    };
    std::vector<std::string> v;
#if defined(__x86_64__)
    if (has({"avx", "avx2", "fma", "f16c", "bmi2"})) v.emplace_back("haswell");
    if (has({"avx"})) v.emplace_back("sandybridge");
    if (has({"sse4_2"})) v.emplace_back("sse42");
    v.emplace_back("x64");
#elif defined(__aarch64__)
    if (has({"asimddp", "asimdhp", "i8mm"})) v.emplace_back("android_armv8.6_1");
    if (has({"asimddp", "asimdhp"})) v.emplace_back("android_armv8.2_2");
    if (has({"asimddp"})) v.emplace_back("android_armv8.2_1");
    v.emplace_back("android_armv8.0_1");
#endif
    return v;
}

std::string g_backend;

bool load_cpu_backend(const std::string & forced) {
    if (!g_backend.empty()) return true;
    std::vector<std::string> variants;
    if (!forced.empty()) variants.push_back(forced);
    for (auto & v : cpu_variants()) variants.push_back(v);
    for (auto & v : variants) {
        std::string lib = "libggml-cpu-" + v + ".so";
        if (ggml_backend_load(lib.c_str()) != nullptr) {
            g_backend = v;
            LOGI("CPU backend: %s", v.c_str());
            return true;
        }
        LOGE("не удалось загрузить %s", lib.c_str());
    }
    return false;
}

std::string jstring_to_std(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void throw_error(JNIEnv * env, const std::string & msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, msg.c_str());
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, special, true);
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, text.data(), (int32_t) text.size(), tokens.data(), n, special, true);
    return tokens;
}

// Длина корректного UTF-8 префикса: токен может оборвать многобайтный символ.
size_t utf8_complete_prefix(const std::string & s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = s[i - 1];
        if ((c & 0xC0) != 0x80) {
            size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
            return back + 1 >= need ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_vexto_llm_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring forced_variant) {
    if (!load_cpu_backend(jstring_to_std(env, forced_variant))) {
        throw_error(env, "нет подходящего CPU-бэкенда ggml");
        return nullptr;
    }
    llama_backend_init();
    return env->NewStringUTF(g_backend.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_vexto_llm_LlamaNative_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    std::string path = jstring_to_std(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    // Перепаковка весов под SIMD копирует их в анонимную память: RSS растёт на размер
    // модели, а на 4 ГБ устройства это своп и замедление в разы. Веса остаются в mmap.
    mp.use_extra_bufts = false;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        throw_error(env, "не удалось загрузить модель: " + path);
        return 0;
    }

    auto * s = new Session();
    s->model = model;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    // KV-кэш в q8_0 вдвое меньше f16; квантованный V требует flash attention.
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.type_k = GGML_TYPE_Q8_0;
    cp.type_v = GGML_TYPE_Q8_0;
    cp.no_perf = true;
    cp.abort_callback = [](void * data) { return static_cast<Session *>(data)->cancel.load(); };
    cp.abort_callback_data = s;

    s->ctx = llama_init_from_model(model, cp);
    if (s->ctx == nullptr) {
        llama_model_free(model);
        delete s;
        throw_error(env, "не удалось создать контекст");
        return 0;
    }
    const char * tmpl = llama_model_chat_template(model, nullptr);
    s->chat_template = tmpl ? tmpl : "chatml";
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jintArray JNICALL
Java_com_vexto_llm_LlamaNative_nativeCountTokens(JNIEnv * env, jobject, jlong handle, jobjectArray texts) {
    auto * s = reinterpret_cast<Session *>(handle);
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    jsize n = env->GetArrayLength(texts);
    std::vector<jint> counts(n);
    for (jsize i = 0; i < n; ++i) {
        auto js = (jstring) env->GetObjectArrayElement(texts, i);
        std::string t = jstring_to_std(env, js);
        env->DeleteLocalRef(js);
        counts[i] = -llama_tokenize(vocab, t.data(), (int32_t) t.size(), nullptr, 0, false, false);
    }
    jintArray out = env->NewIntArray(n);
    env->SetIntArrayRegion(out, 0, n, counts.data());
    return out;
}

// Возвращает [токенов промпта, мс на промпт, токенов ответа, мс на ответ].
JNIEXPORT jlongArray JNICALL
Java_com_vexto_llm_LlamaNative_nativeGenerate(JNIEnv * env, jobject, jlong handle,
                                              jstring jsystem, jstring juser, jstring jgrammar,
                                              jint max_tokens, jfloat temperature, jobject callback) {
    auto * s = reinterpret_cast<Session *>(handle);
    s->cancel = false;
    const llama_vocab * vocab = llama_model_get_vocab(s->model);

    std::string system = jstring_to_std(env, jsystem);
    std::string user = jstring_to_std(env, juser);
    std::string grammar = jstring_to_std(env, jgrammar);

    std::vector<llama_chat_message> msgs;
    if (!system.empty()) msgs.push_back({"system", system.c_str()});
    msgs.push_back({"user", user.c_str()});
    std::vector<char> buf((system.size() + user.size()) * 2 + 256);
    int len = llama_chat_apply_template(s->chat_template.c_str(), msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(s->chat_template.c_str(), msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        throw_error(env, "шаблон чата модели не поддерживается");
        return nullptr;
    }
    std::string prompt(buf.data(), len);
    // Qwen3 по умолчанию рассуждает в <think>. Пустой блок в начале ответа
    // повторяет то, что делает его шаблон при enable_thinking=false.
    if (s->chat_template.find("<think>") != std::string::npos) {
        prompt += "<think>\n\n</think>\n\n";
    }

    std::vector<llama_token> tokens = tokenize(vocab, prompt, true);
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    if ((int) tokens.size() + max_tokens > n_ctx) {
        throw_error(env, "промпт не помещается в контекст: " + std::to_string(tokens.size()) + " токенов");
        return nullptr;
    }

    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (g == nullptr) {
            llama_sampler_free(chain);
            throw_error(env, "ошибка в грамматике GBNF");
            return nullptr;
        }
        llama_sampler_chain_add(chain, g);
    }
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(42));

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_piece = env->GetMethodID(cb_cls, "onPiece", "([B)V");

    llama_memory_clear(llama_get_memory(s->ctx), true);

    int64_t t0 = now_ms();
    const int n_batch = 512;
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        int n = (int) std::min<size_t>(n_batch, tokens.size() - i);
        if (llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            llama_sampler_free(chain);
            if (!s->cancel) throw_error(env, "ошибка llama_decode на промпте");
            return nullptr;
        }
    }
    int64_t t1 = now_ms();

    std::string pending;
    int n_gen = 0;
    bool failed = false;
    while (n_gen < max_tokens && !s->cancel) {
        llama_token tok = llama_sampler_sample(chain, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        ++n_gen;

        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, n);
        size_t ready = utf8_complete_prefix(pending);
        if (ready > 0) {
            jbyteArray bytes = env->NewByteArray((jsize) ready);
            env->SetByteArrayRegion(bytes, 0, (jsize) ready, reinterpret_cast<const jbyte *>(pending.data()));
            env->CallVoidMethod(callback, on_piece, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ready);
            if (env->ExceptionCheck()) { failed = true; break; }
        }
        if (llama_decode(s->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            if (!s->cancel) {
                throw_error(env, "ошибка llama_decode при генерации");
                failed = true;
            }
            break;
        }
    }
    int64_t t2 = now_ms();
    llama_sampler_free(chain);
    if (failed) return nullptr;

    jlong stats[4] = {(jlong) tokens.size(), t1 - t0, n_gen, t2 - t1};
    jlongArray out = env->NewLongArray(4);
    env->SetLongArrayRegion(out, 0, 4, stats);
    return out;
}

JNIEXPORT void JNICALL
Java_com_vexto_llm_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Session *>(handle)->cancel = true;
}

JNIEXPORT void JNICALL
Java_com_vexto_llm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

} // extern "C"
