cmake_minimum_required(VERSION 3.22.1)
project(vexto_llm C CXX)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/llama.cpp)
if (NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "Нет исходников llama.cpp в ${LLAMA_DIR}. Запустите scripts/fetch_llama_cpp.sh")
endif()

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Минимальная сборка: только библиотека llama и CPU-бэкенды.
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)

# CPU-бэкенд собирается в нескольких вариантах (libggml-cpu-<вариант>.so).
# Вариант выбирает наш JNI по /proc/cpuinfo, а не встроенный подсчёт ggml:
# тот не проверяет поддержку AVX со стороны ОС и на гипервизоре может выбрать
# сборку с AVX2, что даёт SIGILL.
set(GGML_BACKEND_DL       ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama.cpp)

add_library(vexto_llm SHARED llm_jni.cpp)
target_link_libraries(vexto_llm PRIVATE llama ggml ggml-base android log)
