// ORT C API headers: microsoft/onnxruntime v1.28.2, MIT license retained in headers.
// Uses sherpa's already-packaged libonnxruntime.so; never embeds/ships a second ORT.
#include <jni.h>
#include <android/NeuralNetworksTypes.h>
#include <dlfcn.h>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>
#include "onnxruntime_c_api.h"

namespace {
const OrtApi* api = nullptr;
const OrtApiBase* base = nullptr;
void* ort_library = nullptr;
std::once_flag loaded;

void load_api() {
    std::call_once(loaded, [] {
        ort_library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!ort_library) throw std::runtime_error("sherpa ONNX Runtime library unavailable");
        using GetApiBase = const OrtApiBase* (ORT_API_CALL*)();
        auto get = reinterpret_cast<GetApiBase>(dlsym(ort_library, "OrtGetApiBase"));
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

const char* accelerator_vendor(std::string_view name) {
    auto matches = [name](std::string_view vendor) {
        return name.substr(0, vendor.size()) == vendor &&
            (name.size() == vendor.size() || name[vendor.size()] == '-' || name[vendor.size()] == '_');
    };
    if (matches("qti") || matches("qcom") || matches("qualcomm")) return "qualcomm";
    if (matches("mtk") || matches("mediatek")) return "mediatek";
    return nullptr;
}

const char* find_npu_driver() {
    // The NDK can identify drivers starting at Android 10. Resolve it dynamically for Android 9.
    using Library = std::unique_ptr<void, decltype(&dlclose)>;
    Library nnapi(dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL), dlclose);
    if (!nnapi) return nullptr;
    using DeviceCount = int (*)(uint32_t*);
    using GetDevice = int (*)(uint32_t, ANeuralNetworksDevice**);
    using DeviceName = int (*)(const ANeuralNetworksDevice*, const char**);
    using DeviceType = int (*)(const ANeuralNetworksDevice*, int32_t*);
    auto count = reinterpret_cast<DeviceCount>(dlsym(nnapi.get(), "ANeuralNetworks_getDeviceCount"));
    auto device = reinterpret_cast<GetDevice>(dlsym(nnapi.get(), "ANeuralNetworks_getDevice"));
    auto name = reinterpret_cast<DeviceName>(dlsym(nnapi.get(), "ANeuralNetworksDevice_getName"));
    auto type = reinterpret_cast<DeviceType>(dlsym(nnapi.get(), "ANeuralNetworksDevice_getType"));
    uint32_t count_value = 0;
    if (!count || !device || !name || !type || count(&count_value) != ANEURALNETWORKS_NO_ERROR) return nullptr;

    const char* vendor = nullptr;
    for (uint32_t i = 0; i < count_value; ++i) {
        ANeuralNetworksDevice* current = nullptr;
        int32_t kind = ANEURALNETWORKS_DEVICE_UNKNOWN;
        if (device(i, &current) != ANEURALNETWORKS_NO_ERROR || !current ||
            type(current, &kind) != ANEURALNETWORKS_NO_ERROR) return nullptr;
        if (kind == ANEURALNETWORKS_DEVICE_CPU) continue;
        // ORT 1.28.2 selects every non-CPU NNAPI device. Refuse mixed topologies
        // so a graph assigned to the EP cannot silently end up on a GPU.
        if (kind != ANEURALNETWORKS_DEVICE_ACCELERATOR) return nullptr;
        const char* driver_name = nullptr;
        if (name(current, &driver_name) != ANEURALNETWORKS_NO_ERROR || !driver_name) return nullptr;
        const char* current_vendor = accelerator_vendor(driver_name);
        if (!current_vendor || (vendor && std::string_view(vendor) != current_vendor)) return nullptr;
        vendor = current_vendor;
    }
    return vendor;
}

using AppendNnapi = OrtStatus* (ORT_API_CALL*)(OrtSessionOptions*, uint32_t);
// NNAPI_FLAG_CPU_DISABLED from the public ORT NNAPI provider factory header.
constexpr uint32_t kNnapiDisableCpu = 0x004;

struct State {
    OrtEnv* environment = nullptr;
    OrtSession* session = nullptr;
    OrtMemoryInfo* memory = nullptr;
    std::string model;
    int threads = 0;
    bool accelerated = false;
    std::string backend = "cpu";
    void release_session() {
        if (session) api->ReleaseSession(session);
        session = nullptr;
    }
    ~State() {
        release_session();
        if (memory) api->ReleaseMemoryInfo(memory);
        if (environment) api->ReleaseEnv(environment);
    }
};
struct Value {
    OrtValue* value = nullptr;
    ~Value() { if(value) api->ReleaseValue(value); }
};

void create_session(State& state, AppendNnapi append = nullptr) {
    OrtSessionOptions* options = nullptr;
    check(api->CreateSessionOptions(&options));
    try {
        check(api->SetIntraOpNumThreads(options, state.threads));
        check(api->SetInterOpNumThreads(options, 1));
        check(api->SetSessionExecutionMode(options, ORT_SEQUENTIAL));
        check(api->DisableMemPattern(options));
        check(api->DisableCpuMemArena(options));
        if (append) {
            check(api->AddSessionConfigEntry(options, "session.record_ep_graph_assignment_info", "1"));
            check(append(options, kNnapiDisableCpu));
        }
        check(api->CreateSession(state.environment, state.model.c_str(), options, &state.session));
    } catch (...) { api->ReleaseSessionOptions(options); throw; }
    api->ReleaseSessionOptions(options);
    size_t inputs = 0, outputs = 0;
    check(api->SessionGetInputCount(state.session, &inputs));
    check(api->SessionGetOutputCount(state.session, &outputs));
    if (inputs != 2 || outputs != 1) throw std::runtime_error("Expected GigaAM-Emo two-input/one-output graph");
}

size_t nnapi_partitions(const State& state) {
    if (!api->Session_GetEpGraphAssignmentInfo || !api->EpAssignedSubgraph_GetEpName ||
        !api->EpAssignedSubgraph_GetNodes) {
        throw std::runtime_error("ORT graph assignment inspection unavailable");
    }
    const OrtEpAssignedSubgraph* const* graphs = nullptr;
    size_t graph_count = 0;
    check(api->Session_GetEpGraphAssignmentInfo(state.session, &graphs, &graph_count));
    size_t partitions = 0;
    for (size_t i = 0; i < graph_count; ++i) {
        const char* provider = nullptr;
        check(api->EpAssignedSubgraph_GetEpName(graphs[i], &provider));
        if (!provider || std::string_view(provider) != "NnapiExecutionProvider") continue;
        const OrtEpAssignedNode* const* nodes = nullptr;
        size_t node_count = 0;
        check(api->EpAssignedSubgraph_GetNodes(graphs[i], &nodes, &node_count));
        if (node_count > 0) ++partitions;
    }
    return partitions;
}

jlong create(JNIEnv* env, jstring path, jint threads, bool allow_npu) {
    try {
        load_api();
        if (!path || threads < 1 || threads > 4) throw std::runtime_error("Invalid emotion model or thread count");
        const char* raw = env->GetStringUTFChars(path, nullptr);
        if (!raw) return 0;
        std::string model(raw);
        env->ReleaseStringUTFChars(path, raw);
        auto state = std::make_unique<State>();
        state->model = std::move(model);
        state->threads = threads;
        check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "vexto-emotion", &state->environment));
        if (allow_npu) {
            const char* vendor = find_npu_driver();
            auto append = reinterpret_cast<AppendNnapi>(
                dlsym(ort_library, "OrtSessionOptionsAppendExecutionProvider_Nnapi"));
            if (!vendor) {
                state->backend = "cpu:no_supported_nnapi_driver";
            } else if (!append) {
                state->backend = "cpu:nnapi_provider_unavailable";
            } else {
                try {
                    create_session(*state, append);
                    size_t partitions = nnapi_partitions(*state);
                    if (partitions > 0) {
                        state->backend = std::string("nnapi:") + vendor + ":" + std::to_string(partitions);
                        state->accelerated = true;
                    } else {
                        state->backend = "cpu:nnapi_no_partition";
                    }
                } catch (const std::exception&) {
                    state->backend = "cpu:nnapi_setup_failed";
                }
                if (!state->accelerated) state->release_session();
            }
        }
        if (!state->session) create_session(*state);
        check(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &state->memory));
        return reinterpret_cast<jlong>(state.release());
    } catch (const std::exception& error) { fail(env,error); return 0; }
}

std::array<float, 4> infer(State* state, std::vector<float>& data, jint frames) {
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
    return {probabilities[0], probabilities[1], probabilities[2], probabilities[3]};
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_create(JNIEnv* env, jobject, jstring path, jint threads) {
    return create(env, path, threads, false);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_createAccelerated(JNIEnv* env, jobject, jstring path, jint threads) {
    return create(env, path, threads, true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_npuDriver(JNIEnv* env, jobject) {
    const char* vendor = find_npu_driver();
    return vendor ? env->NewStringUTF(vendor) : nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_backend(JNIEnv* env, jobject, jlong handle) {
    auto* state = reinterpret_cast<State*>(handle);
    return env->NewStringUTF(state ? state->backend.c_str() : "cpu");
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vexto_engine_pipeline_EmotionNative_predict(JNIEnv* env, jobject, jlong handle, jfloatArray input, jint frames) {
    try {
        auto* state = reinterpret_cast<State*>(handle);
        if (!state || !input || frames < 1 || frames > 801 || env->GetArrayLength(input) != 64*frames)
            throw std::runtime_error("Expected bounded GigaAM-Emo features[1,64,T]");
        std::vector<float> data(64*frames);
        env->GetFloatArrayRegion(input, 0, static_cast<jsize>(data.size()), data.data());
        if (env->ExceptionCheck()) return nullptr;
        for(float v:data) if(!std::isfinite(v)) throw std::runtime_error("Nonfinite log-mel features");
        std::array<float, 4> probabilities;
        try {
            if (!state->session) create_session(*state);
            probabilities = infer(state, data, frames);
        } catch (const std::exception&) {
            if (!state->accelerated) throw;
            state->release_session();
            state->accelerated = false;
            state->backend = "cpu:nnapi_run_failed";
            create_session(*state);
            probabilities = infer(state, data, frames);
        }
        auto result = env->NewFloatArray(4);
        if (result) env->SetFloatArrayRegion(result, 0, 4, probabilities.data());
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
