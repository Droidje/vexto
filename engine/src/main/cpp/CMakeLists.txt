cmake_minimum_required(VERSION 3.22)
project(vexto_emotion C)

set(ORT_HEADERS "${CMAKE_CURRENT_LIST_DIR}/../../../libs/ort/headers")
if(NOT EXISTS "${ORT_HEADERS}/onnxruntime_c_api.h")
    message(FATAL_ERROR "Run bash scripts/fetch_models.sh --aar-only to install ONNX Runtime headers")
endif()

add_library(vexto_emotion SHARED gigaam_emo.c)
target_include_directories(vexto_emotion SYSTEM PRIVATE "${ORT_HEADERS}")
target_compile_options(vexto_emotion PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vexto_emotion PRIVATE dl)
