cmake_minimum_required(VERSION 3.22.1)
project(encodec_android LANGUAGES CXX)

add_library(
    encodec_android
    SHARED
    encodec_jni.cpp
    encodec/encodec.cpp
)

target_compile_features(encodec_android PRIVATE cxx_std_20)
target_compile_definitions(encodec_android PRIVATE ENCODEC_RUNTIME_MODELS_ONLY=1 EIGEN_DONT_PARALLELIZE=1)
target_include_directories(
    encodec_android
    PRIVATE
    encodec
    third_party/eigen
)

# The decoder is the playback hot path even in a debuggable APK. Arm64 already
# enables NEON, and avoiding OpenMP keeps inference on one predictable worker.
target_compile_options(
    encodec_android PRIVATE
    -O3 -ffast-math -ffunction-sections -fdata-sections -fvisibility=hidden
)
target_link_options(encodec_android PRIVATE -Wl,--gc-sections)
target_link_libraries(encodec_android PRIVATE android log)
