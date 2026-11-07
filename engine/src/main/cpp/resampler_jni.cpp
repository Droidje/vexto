#include <jni.h>
#include "resampler_fir.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vexto_engine_audio_NativeResampler_render(
        JNIEnv* env, jobject, jfloatArray input, jfloatArray taps, jfloatArray output,
        jlong bufferStart, jlong produced, jint up, jint down, jint phases, jint half, jint count) {
    if (up <= 0 || down <= 0 || phases <= 0 || half <= 0 || count <= 0 || produced < 0) return JNI_FALSE;
    if (count > env->GetArrayLength(output) ||
        static_cast<int64_t>(phases) * 2 * half != env->GetArrayLength(taps)) return JNI_FALSE;
    const int64_t first = produced * down / up - half + 1 - bufferStart;
    const int64_t last = (produced + count - 1) * down / up - half + 1 - bufferStart;
    if (first < 0 || last + 2 * half > env->GetArrayLength(input)) return JNI_FALSE;

    // These short, allocation-free critical regions avoid copying the history/table for each block.
    // Nested GetPrimitiveArrayCritical is allowed by JNI; no other JNI calls occur while pinned.
    auto* samples = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(input, nullptr));
    if (!samples) return JNI_FALSE;
    auto* coefficients = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(taps, nullptr));
    if (!coefficients) {
        env->ReleasePrimitiveArrayCritical(input, samples, JNI_ABORT);
        return JNI_FALSE;
    }
    auto* result = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (!result) {
        env->ReleasePrimitiveArrayCritical(taps, coefficients, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(input, samples, JNI_ABORT);
        return JNI_FALSE;
    }
    vexto::audio::render(samples, coefficients, result, bufferStart, produced, up, down, phases, half, count);
    env->ReleasePrimitiveArrayCritical(output, result, 0);
    env->ReleasePrimitiveArrayCritical(taps, coefficients, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(input, samples, JNI_ABORT);
    return JNI_TRUE;
}
