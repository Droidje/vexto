// WavLM overlap inference. Reuses sherpa's sole ORT 1.28.2 runtime.
#include <jni.h>
#include <dlfcn.h>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"

namespace {
const OrtApi* api = nullptr;
std::once_flag loaded;
void load_api() {
    std::call_once(loaded, [] {
        auto library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("ONNX runtime unavailable");
        using Get = const OrtApiBase* (ORT_API_CALL*)();
        auto get = reinterpret_cast<Get>(dlsym(library, "OrtGetApiBase"));
        if (!get) throw std::runtime_error("ORT C API unavailable");
        auto base = get();
        if (std::string(base->GetVersionString()) != "1.28.2")
            throw std::runtime_error("Overlap requires sherpa ORT 1.28.2");
        api = base->GetApi(ORT_API_VERSION);
        if (!api) throw std::runtime_error("ORT API incompatible");
    });
}
void check(OrtStatus* status) {
    if (!status) return;
    std::string message = api->GetErrorMessage(status);
    api->ReleaseStatus(status);
    throw std::runtime_error(message);
}
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
struct State {
    OrtEnv* environment = nullptr;
    OrtSession* session = nullptr;
    OrtMemoryInfo* memory = nullptr;
    ~State() {
        if (session) api->ReleaseSession(session);
        if (memory) api->ReleaseMemoryInfo(memory);
        if (environment) api->ReleaseEnv(environment);
    }
};
struct Value {
    OrtValue* value = nullptr;
    ~Value() { if (value) api->ReleaseValue(value); }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vexto_engine_pipeline_OverlapNative_create(JNIEnv* env, jobject, jstring path, jint threads) {
    try {
        load_api();
        if (!path || threads < 1 || threads > 4) throw std::runtime_error("Invalid overlap configuration");
        const char* raw = env->GetStringUTFChars(path, nullptr);
        if (!raw) return 0;
        std::string model(raw);
        env->ReleaseStringUTFChars(path, raw);
        auto state = std::make_unique<State>();
        check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "vexto-overlap", &state->environment));
        OrtSessionOptions* options = nullptr;
        check(api->CreateSessionOptions(&options));
        try {
            check(api->SetIntraOpNumThreads(options, threads));
            check(api->SetInterOpNumThreads(options, 1));
            check(api->SetSessionExecutionMode(options, ORT_SEQUENTIAL));
            check(api->DisableMemPattern(options));
            check(api->DisableCpuMemArena(options));
            check(api->CreateSession(state->environment, model.c_str(), options, &state->session));
        } catch (...) { api->ReleaseSessionOptions(options); throw; }
        api->ReleaseSessionOptions(options);
        size_t inputs = 0, outputs = 0;
        check(api->SessionGetInputCount(state->session, &inputs));
        check(api->SessionGetOutputCount(state->session, &outputs));
        if (inputs != 1 || outputs != 1) throw std::runtime_error("Expected overlap one-input/one-output graph");
        check(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &state->memory));
        return reinterpret_cast<jlong>(state.release());
    } catch (const std::exception& error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_vexto_engine_pipeline_OverlapNative_predict(JNIEnv* env, jobject, jlong handle, jfloatArray input) {
    try {
        auto* state = reinterpret_cast<State*>(handle);
        if (!state || !input || env->GetArrayLength(input) != 64000)
            throw std::runtime_error("Expected overlap audio float32[1,64000]");
        std::vector<float> data(64000);
        env->GetFloatArrayRegion(input, 0, 64000, data.data());
        if (env->ExceptionCheck()) return 0;
        for (float value : data) if (!std::isfinite(value) || value < -1 || value > 1)
            throw std::runtime_error("Invalid PCM samples");
        int64_t shape[] = {1, 64000};
        Value audio, output;
        check(api->CreateTensorWithDataAsOrtValue(state->memory, data.data(), data.size()*sizeof(float),
              shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &audio.value));
        const char* input_names[] = {"audio"};
        const char* output_names[] = {"score"};
        const OrtValue* values[] = {audio.value};
        check(api->Run(state->session, nullptr, input_names, values, 1, output_names, 1, &output.value));
        OrtTensorTypeAndShapeInfo* info = nullptr;
        check(api->GetTensorTypeAndShape(output.value, &info));
        size_t count = 0;
        ONNXTensorElementDataType type;
        auto status = api->GetTensorElementType(info, &type);
        if (!status) status = api->GetTensorShapeElementCount(info, &count);
        api->ReleaseTensorTypeAndShapeInfo(info);
        check(status);
        if (count != 1 || type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)
            throw std::runtime_error("Expected overlap score float32[1]");
        float* score = nullptr;
        check(api->GetTensorMutableData(output.value, reinterpret_cast<void**>(&score)));
        if (!std::isfinite(*score) || *score < 0 || *score > 1)
            throw std::runtime_error("Invalid overlap score");
        return *score;
    } catch (const std::exception& error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vexto_engine_pipeline_OverlapNative_release(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<State*>(handle);
}
