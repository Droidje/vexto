// ORT C API headers: microsoft/onnxruntime v1.28.2, MIT license retained in headers.
// Uses sherpa's already-packaged libonnxruntime.so; never embeds/ships a second ORT.
#include <jni.h>
#include <dlfcn.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"

namespace {
const OrtApi* api = nullptr;
const OrtApiBase* base = nullptr;
std::once_flag loaded;

void load_api() {
    std::call_once(loaded, [] {
        void* library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("sherpa ONNX Runtime library unavailable");
        using GetApiBase = const OrtApiBase* (ORT_API_CALL*)();
        auto get = reinterpret_cast<GetApiBase>(dlsym(library, "OrtGetApiBase"));
        if (!get) throw std::runtime_error("ORT C API symbol unavailable");
        base = get();
        if (std::string(base->GetVersionString()) != "1.28.2")
            throw std::runtime_error("Emotion adapter requires sherpa ORT1.28.2; do not mix Java ORT runtimes");
        api = base->GetApi(ORT_API_VERSION);
        if (!api) throw std::runtime_error("ORT C API version incompatible");
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
    ~Value() { if(value) api->ReleaseValue(value); }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_create(JNIEnv* env, jobject, jstring path, jint threads) {
    try {
        load_api();
        if (threads < 1 || threads > 4) throw std::runtime_error("Invalid emotion thread count");
        const char* raw = env->GetStringUTFChars(path, nullptr);
        if (!raw) return 0;
        std::string model(raw); env->ReleaseStringUTFChars(path, raw);
        auto state = std::make_unique<State>();
        check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "vexto-emotion", &state->environment));
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
        check(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &state->memory));
        size_t inputs = 0, outputs = 0;
        check(api->SessionGetInputCount(state->session, &inputs));
        check(api->SessionGetOutputCount(state->session, &outputs));
        if (inputs != 2 || outputs != 1) throw std::runtime_error("Expected GigaAM-Emo two-input/one-output graph");
        return reinterpret_cast<jlong>(state.release());
    } catch (const std::exception& error) { fail(env,error); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_predict(JNIEnv* env, jobject, jlong handle, jfloatArray input, jint frames) {
    try {
        auto* state = reinterpret_cast<State*>(handle);
        if (!state || frames < 1 || frames > 801 || env->GetArrayLength(input) != 64*frames)
            throw std::runtime_error("Expected bounded GigaAM-Emo features[1,64,T]");
        std::vector<float> data(64*frames);
        env->GetFloatArrayRegion(input, 0, static_cast<jsize>(data.size()), data.data());
        for(float v:data) if(!std::isfinite(v)) throw std::runtime_error("Nonfinite log-mel features");
        int64_t shape[] = {1,64,frames}, length_shape[] = {1}, length = frames;
        Value features, lengths, output;
        check(api->CreateTensorWithDataAsOrtValue(state->memory,data.data(),data.size()*sizeof(float),shape,3,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&features.value));
        check(api->CreateTensorWithDataAsOrtValue(state->memory,&length,sizeof(length),length_shape,1,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,&lengths.value));
        const char* input_names[] = {"features","feature_lengths"};
        const char* output_names[] = {"probs"};
        const OrtValue* values[] = {features.value,lengths.value};
        check(api->Run(state->session,nullptr,input_names,values,2,output_names,1,&output.value));
        OrtTensorTypeAndShapeInfo* info = nullptr;
        check(api->GetTensorTypeAndShape(output.value,&info));
        size_t count = 0;
        ONNXTensorElementDataType type;
        size_t rank = 0;
        int64_t dims[2] = {0,0};
        auto status = api->GetTensorElementType(info,&type);
        if (!status) status = api->GetDimensionsCount(info,&rank);
        if (!status && rank == 2) status = api->GetDimensions(info,dims,2);
        if (!status) status = api->GetTensorShapeElementCount(info,&count);
        api->ReleaseTensorTypeAndShapeInfo(info);
        check(status);
        if (count != 4 || type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || rank != 2 || dims[0] != 1 || dims[1] != 4)
            throw std::runtime_error("Expected GigaAM-Emo probs float32[1,4]");
        float* probabilities = nullptr;
        check(api->GetTensorMutableData(output.value,reinterpret_cast<void**>(&probabilities)));
        double sum = 0;
        for(int i=0;i<4;i++) {
            const float p = probabilities[i];
            if(!std::isfinite(p) || p < 0 || p > 1) throw std::runtime_error("Invalid emotion probability");
            sum += p;
        }
        if(std::abs(sum-1)>0.001) throw std::runtime_error("Invalid emotion probability sum");
        auto result = env->NewFloatArray(4);
        if (result) env->SetFloatArrayRegion(result,0,4,probabilities);
        return result;
    } catch (const std::exception& error) { fail(env,error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_release(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<State*>(handle);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_runtimeVersion(JNIEnv* env, jobject) {
    try { load_api(); return env->NewStringUTF(base->GetVersionString()); }
    catch (const std::exception& error) { fail(env,error); return nullptr; }
}
