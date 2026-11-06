#include <dlfcn.h>
#include <jni.h>
#include <onnxruntime_c_api.h>
#include <stdint.h>

typedef struct {
    void *library;
    const OrtApi *api;
    OrtEnv *env;
    OrtSession *session;
    OrtMemoryInfo *cpu;
} EmotionSession;

static void fail(JNIEnv *jni, const char *message) {
    jclass type = (*jni)->FindClass(jni, "java/lang/IllegalStateException");
    if (type) (*jni)->ThrowNew(jni, type, message);
}

static void release(EmotionSession *s) {
    if (!s) return;
    if (s->cpu) s->api->ReleaseMemoryInfo(s->cpu);
    if (s->session) s->api->ReleaseSession(s->session);
    if (s->env) s->api->ReleaseEnv(s->env);
    if (s->library) dlclose(s->library);
    free(s);
}

static int succeeded(JNIEnv *jni, const OrtApi *api, OrtStatus *status) {
    if (!status) return 1;
    fail(jni, api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_vexto_engine_emotion_GigaAmOrt_nativeOpen(
        JNIEnv *jni, jclass type __attribute__((unused)), jstring path, jint threads) {
    EmotionSession *s = calloc(1, sizeof(*s));
    if (!s) {
        fail(jni, "Cannot allocate emotion session");
        return 0;
    }
    // Reuse the exact runtime already shipped by Sherpa, with no second native binary.
    s->library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
    const OrtApiBase *(*get_api_base)(void) = s->library ? dlsym(s->library, "OrtGetApiBase") : NULL;
    s->api = get_api_base ? get_api_base()->GetApi(ORT_API_VERSION) : NULL;
    if (!s->api) {
        fail(jni, "ONNX Runtime C API is unavailable");
        release(s);
        return 0;
    }

    const OrtApi *api = s->api;
    OrtSessionOptions *opts = NULL;
    const char *filename = (*jni)->GetStringUTFChars(jni, path, NULL);
    int ready = filename &&
            succeeded(jni, api, api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "vexto-emo", &s->env)) &&
            succeeded(jni, api, api->CreateSessionOptions(&opts)) &&
            succeeded(jni, api, api->SetIntraOpNumThreads(opts, threads)) &&
            succeeded(jni, api, api->SetInterOpNumThreads(opts, 1)) &&
            succeeded(jni, api, api->DisableCpuMemArena(opts)) &&
            succeeded(jni, api, api->DisableMemPattern(opts)) &&
            succeeded(jni, api, api->CreateSession(s->env, filename, opts, &s->session)) &&
            succeeded(jni, api, api->CreateCpuMemoryInfo(OrtDeviceAllocator, OrtMemTypeDefault, &s->cpu));
    if (opts) api->ReleaseSessionOptions(opts);
    if (filename) (*jni)->ReleaseStringUTFChars(jni, path, filename);
    if (ready) return (jlong)(intptr_t)s;
    release(s);
    return 0;
}

JNIEXPORT jfloatArray JNICALL Java_com_vexto_engine_emotion_GigaAmOrt_nativeRun(
        JNIEnv *jni, jclass type __attribute__((unused)), jlong handle, jfloatArray features, jint frames) {
    EmotionSession *s = (EmotionSession *)(intptr_t)handle;
    if (!s || frames <= 0 || frames > 801 || (*jni)->GetArrayLength(jni, features) != 64 * frames) {
        fail(jni, "Invalid GigaAM-Emo input");
        return NULL;
    }
    jfloat *data = (*jni)->GetFloatArrayElements(jni, features, NULL);
    if (!data) return NULL;

    const OrtApi *api = s->api;
    const int64_t shape[] = {1, 64, frames}, lens_shape[] = {1};
    int64_t length = frames;
    const char *inputs[] = {"features", "feature_lengths"}, *outputs[] = {"probs"};
    OrtValue *tensors[2] = {NULL, NULL}, *probabilities = NULL;
    OrtTensorTypeAndShapeInfo *info = NULL;
    jfloatArray result = NULL;
    int64_t dimensions[2];
    size_t rank;
    ONNXTensorElementDataType element_type;
    void *scores = NULL;

    if (!succeeded(jni, api, api->CreateTensorWithDataAsOrtValue(s->cpu, data, 64 * frames * sizeof(*data),
            shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensors[0])) ||
        !succeeded(jni, api, api->CreateTensorWithDataAsOrtValue(s->cpu, &length, sizeof(length),
            lens_shape, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &tensors[1])) ||
        !succeeded(jni, api, api->Run(s->session, NULL, inputs, (const OrtValue *const *)tensors, 2,
            outputs, 1, &probabilities)) ||
        !succeeded(jni, api, api->GetTensorTypeAndShape(probabilities, &info)) ||
        !succeeded(jni, api, api->GetTensorElementType(info, &element_type)) ||
        !succeeded(jni, api, api->GetDimensionsCount(info, &rank))) goto cleanup;
    if (rank != 2 || element_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
        fail(jni, "Expected float probabilities with shape [1,4]");
        goto cleanup;
    }
    if (!succeeded(jni, api, api->GetDimensions(info, dimensions, 2))) goto cleanup;
    if (dimensions[0] != 1 || dimensions[1] != 4) {
        fail(jni, "Expected four GigaAM-Emo probabilities");
        goto cleanup;
    }
    if (!succeeded(jni, api, api->GetTensorMutableData(probabilities, &scores))) goto cleanup;
    result = (*jni)->NewFloatArray(jni, 4);
    if (result) (*jni)->SetFloatArrayRegion(jni, result, 0, 4, scores);

cleanup:
    if (info) api->ReleaseTensorTypeAndShapeInfo(info);
    if (probabilities) api->ReleaseValue(probabilities);
    if (tensors[1]) api->ReleaseValue(tensors[1]);
    if (tensors[0]) api->ReleaseValue(tensors[0]);
    (*jni)->ReleaseFloatArrayElements(jni, features, data, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL Java_com_vexto_engine_emotion_GigaAmOrt_nativeClose(
        JNIEnv *jni __attribute__((unused)), jclass type __attribute__((unused)), jlong handle) {
    release((EmotionSession *)(intptr_t)handle);
}
