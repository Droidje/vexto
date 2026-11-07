#include <jni.h>
#if defined(__x86_64__)
#include <cpuid.h>
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vexto_engine_runtime_NativeCpuDispatch_supportsAvx2(JNIEnv*, jobject) {
#if defined(__x86_64__)
    unsigned a, b, c, d;
    if (!__get_cpuid(1, &a, &b, &c, &d)) return false;
    constexpr unsigned required = bit_SSE4_2 | bit_AVX | bit_FMA | bit_F16C | bit_OSXSAVE;
    if ((c & required) != required) return false;
    unsigned lo, hi;
    __asm__ volatile("xgetbv" : "=a"(lo), "=d"(hi) : "c"(0));
    if ((lo & 6) != 6) return false; // OS saves both SSE and AVX registers.
    if (!__get_cpuid_count(7, 0, &a, &b, &c, &d)) return false;
    return (b & bit_AVX2) != 0;
#else
    return false;
#endif
}
