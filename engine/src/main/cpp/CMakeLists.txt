cmake_minimum_required(VERSION 3.22)
project(vexto_emotion LANGUAGES CXX)
add_library(vexto_emotion_jni SHARED emotion_jni.cpp)
target_compile_features(vexto_emotion_jni PRIVATE cxx_std_17)
target_include_directories(vexto_emotion_jni PRIVATE include)
target_link_libraries(vexto_emotion_jni PRIVATE dl)
# No ORT binary here: dlopen the sole libonnxruntime.so supplied by sherpa-onnx AAR.
