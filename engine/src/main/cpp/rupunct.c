#include <dlfcn.h>
#include <jni.h>
#include <onnxruntime_c_api.h>
#include <stdlib.h>
#include <stdint.h>

#define MAX_TOKENS 512
#define LABEL_COUNT 33

typedef struct {
    void *library;
    const OrtApi *api;
    OrtEnv *env;
    OrtSession *session;
    OrtMemoryInfo *cpu;
} PunctSession;

static void fail(JNIEnv *jni, const char *message) {
    jclass type = (*jni)->FindClass(jni, "java/lang/IllegalStateException");
    if (type) (*jni)->ThrowNew(jni, type, message);
}

static void release(PunctSession *s) {
    if (!s) return;
    if (s->cpu && s->api) s->api->ReleaseMemoryInfo(s->cpu);
    if (s->session && s->api) s->api->ReleaseSession(s->session);
    if (s->env && s->api) s->api->ReleaseEnv(s->env);
    if (s->library) dlclose(s->library);
    free(s);
}

static int succeeded(JNIEnv *jni, const OrtApi *api, OrtStatus *status) {
    if (!status) return 1;
    fail(jni, api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_vexto_engine_punctuation_RuPunctOrt_nativeOpen(
        JNIEnv *jni, jclass type __attribute__((unused)), jstring path, jint threads) {
    PunctSession *s = calloc(1, sizeof(*s));
    if (!s) {
        fail(jni, "Cannot allocate RUPunct session");
        return 0;
    }
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
    int ready = filename && threads >= 1 && threads <= 4 &&
            succeeded(jni, api, api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "vexto-punct", &s->env)) &&
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

JNIEXPORT jintArray JNICALL Java_com_vexto_engine_punctuation_RuPunctOrt_nativeLabels(
        JNIEnv *jni, jclass type __attribute__((unused)), jlong handle, jintArray token_ids) {
    PunctSession *s = (PunctSession *)(intptr_t)handle;
    jsize count = token_ids ? (*jni)->GetArrayLength(jni, token_ids) : 0;
    if (!s || count < 2 || count > MAX_TOKENS) {
        fail(jni, "Invalid RUPunct token sequence");
        return NULL;
    }
    jint *ids = (*jni)->GetIntArrayElements(jni, token_ids, NULL);
    if (!ids) return NULL;

    int64_t *input = calloc((size_t)count, sizeof(int64_t));
    int64_t *mask = calloc((size_t)count, sizeof(int64_t));
    int64_t *types = calloc((size_t)count, sizeof(int64_t));
    const OrtApi *api = s->api;
    const int64_t shape[] = {1, count};
    const char *names[] = {"input_ids", "attention_mask", "token_type_ids"};
    const char *outputs[] = {"logits"};
    OrtValue *tensors[3] = {NULL, NULL, NULL};
    OrtValue *logits = NULL;
    OrtTensorTypeAndShapeInfo *info = NULL;
    jintArray result = NULL;
    void *data = NULL;
    int64_t dimensions[3];
    size_t rank = 0;
    ONNXTensorElementDataType element = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
    jsize i;

    if (!input || !mask || !types) {
        fail(jni, "Cannot allocate RUPunct tensors");
        goto cleanup;
    }
    for (i = 0; i < count; i++) {
        input[i] = ids[i];
        mask[i] = 1;
    }
    if (!succeeded(jni, api, api->CreateTensorWithDataAsOrtValue(s->cpu, input, (size_t)count * sizeof(*input),
            shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &tensors[0])) ||
        !succeeded(jni, api, api->CreateTensorWithDataAsOrtValue(s->cpu, mask, (size_t)count * sizeof(*mask),
            shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &tensors[1])) ||
        !succeeded(jni, api, api->CreateTensorWithDataAsOrtValue(s->cpu, types, (size_t)count * sizeof(*types),
            shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &tensors[2])) ||
        !succeeded(jni, api, api->Run(s->session, NULL, names, (const OrtValue *const *)tensors, 3,
            outputs, 1, &logits)) ||
        !succeeded(jni, api, api->GetTensorTypeAndShape(logits, &info)) ||
        !succeeded(jni, api, api->GetTensorElementType(info, &element)) ||
        !succeeded(jni, api, api->GetDimensionsCount(info, &rank))) goto cleanup;
    if (element != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || rank != 3) {
        fail(jni, "Expected float RUPunct logits [1, tokens, 33]");
        goto cleanup;
    }
    if (!succeeded(jni, api, api->GetDimensions(info, dimensions, 3))) goto cleanup;
    if (dimensions[0] != 1 || dimensions[1] != count || dimensions[2] != LABEL_COUNT) {
        fail(jni, "Unexpected RUPunct logits shape");
        goto cleanup;
    }
    if (!succeeded(jni, api, api->GetTensorMutableData(logits, &data))) goto cleanup;
    result = (*jni)->NewIntArray(jni, count);
    if (result) {
        jint *labels = (*jni)->GetIntArrayElements(jni, result, NULL);
        const float *scores = data;
        if (labels) {
            for (i = 0; i < count; i++) {
                int best = 0;
                jsize label;
                for (label = 1; label < LABEL_COUNT; label++) {
                    if (scores[(size_t)i * LABEL_COUNT + label] > scores[(size_t)i * LABEL_COUNT + best]) best = label;
                }
                labels[i] = best;
            }
            (*jni)->ReleaseIntArrayElements(jni, result, labels, 0);
        }
    }

cleanup:
    if (info) api->ReleaseTensorTypeAndShapeInfo(info);
    if (logits) api->ReleaseValue(logits);
    if (tensors[2]) api->ReleaseValue(tensors[2]);
    if (tensors[1]) api->ReleaseValue(tensors[1]);
    if (tensors[0]) api->ReleaseValue(tensors[0]);
    free(types);
    free(mask);
    free(input);
    (*jni)->ReleaseIntArrayElements(jni, token_ids, ids, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL Java_com_vexto_engine_punctuation_RuPunctOrt_nativeClose(
        JNIEnv *jni __attribute__((unused)), jclass type __attribute__((unused)), jlong handle) {
    release((PunctSession *)(intptr_t)handle);
}
