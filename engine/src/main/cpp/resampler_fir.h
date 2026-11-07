#pragma once

#include <cstdint>

#if defined(__aarch64__)
#include <arm_neon.h>
#elif defined(__x86_64__)
#include <emmintrin.h>
#endif

namespace vexto::audio {

// Baseline SIMD for both packaged ABIs. No AVX/optional CPU extension is required.
inline float dot(const float* input, const float* taps, int count) {
    int i = 0;
    float sum = 0.0f;
#if defined(__aarch64__)
    float32x4_t a = vdupq_n_f32(0.0f);
    float32x4_t b = vdupq_n_f32(0.0f);
    for (; i + 7 < count; i += 8) {
        a = vaddq_f32(a, vmulq_f32(vld1q_f32(input + i), vld1q_f32(taps + i)));
        b = vaddq_f32(b, vmulq_f32(vld1q_f32(input + i + 4), vld1q_f32(taps + i + 4)));
    }
    sum = vaddvq_f32(vaddq_f32(a, b));
#elif defined(__x86_64__)
    __m128 a = _mm_setzero_ps();
    __m128 b = _mm_setzero_ps();
    for (; i + 7 < count; i += 8) {
        a = _mm_add_ps(a, _mm_mul_ps(_mm_loadu_ps(input + i), _mm_loadu_ps(taps + i)));
        b = _mm_add_ps(b, _mm_mul_ps(_mm_loadu_ps(input + i + 4), _mm_loadu_ps(taps + i + 4)));
    }
    float lanes[4];
    _mm_storeu_ps(lanes, _mm_add_ps(a, b));
    sum = (lanes[0] + lanes[1]) + (lanes[2] + lanes[3]);
#endif
    for (; i < count; ++i) sum += input[i] * taps[i];
    return sum;
}

inline void render(const float* input, const float* taps, float* output,
                   int64_t bufferStart, int64_t produced, int up, int down,
                   int phases, int half, int count) {
    int64_t pos = produced * down;
    for (int n = 0; n < count; ++n, pos += down) {
        const int64_t base = pos / up;
        const int phase = static_cast<int>((pos % up) * phases / up);
        const int64_t k = base - half + 1 - bufferStart;
        output[n] = dot(input + k, taps + phase * 2 * half, 2 * half);
    }
}

} // namespace vexto::audio
