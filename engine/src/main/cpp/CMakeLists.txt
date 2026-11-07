cmake_minimum_required(VERSION 3.22)
project(vexto_emotion LANGUAGES C CXX)
add_library(vexto_cpu SHARED cpu_features.cpp)
target_compile_features(vexto_cpu PRIVATE cxx_std_17)

add_library(vexto_resampler SHARED resampler_jni.cpp)
target_compile_features(vexto_resampler PRIVATE cxx_std_17)
# Keep the existing float coefficients; SIMD changes only accumulation order, not the filter.
target_compile_options(vexto_resampler PRIVATE -O3 -ffp-contract=off -Wall -Wextra -Werror)

# Both adapters dlopen sherpa's sole ORT runtime; prepare pinned 1.28.2 headers offline.
set(ORT_HEADERS "${CMAKE_CURRENT_LIST_DIR}/include")
if(NOT EXISTS "${ORT_HEADERS}/onnxruntime_c_api.h")
    message(FATAL_ERROR "Run python scripts/prepare_emotion_runtime.py to install pinned ORT 1.28.2 headers")
endif()

add_library(vexto_emotion_jni SHARED emotion_jni.cpp)
target_compile_features(vexto_emotion_jni PRIVATE cxx_std_17)
target_include_directories(vexto_emotion_jni SYSTEM PRIVATE "${ORT_HEADERS}")
target_link_libraries(vexto_emotion_jni PRIVATE dl)

# Preserve the incoming dev GigaAmOrt API for UtteranceEmotions callers.
add_library(vexto_emotion SHARED gigaam_emo.c)
target_include_directories(vexto_emotion SYSTEM PRIVATE "${ORT_HEADERS}")
target_compile_options(vexto_emotion PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vexto_emotion PRIVATE dl)

add_library(vexto_punct SHARED rupunct.c)
target_include_directories(vexto_punct SYSTEM PRIVATE "${ORT_HEADERS}")
target_compile_options(vexto_punct PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vexto_punct PRIVATE dl)
