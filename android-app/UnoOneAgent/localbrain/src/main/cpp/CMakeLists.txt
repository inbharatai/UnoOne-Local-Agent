cmake_minimum_required(VERSION 3.22.1)
project(unoone_qwen LANGUAGES C CXX ASM)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "Qwen runtime requires Android arm64-v8a")
endif()
include(FetchContent)
# Bound native build parallelism even on hosts exposing many CPUs with limited RAM.
set_property(GLOBAL PROPERTY JOB_POOLS unoone_compile=2 unoone_link=1)
set(CMAKE_JOB_POOL_COMPILE unoone_compile)
set(CMAKE_JOB_POOL_LINK unoone_link)
# Immutable upstream archive, verified before extraction. No moving branches/submodules.
FetchContent_Declare(mnn
  URL https://codeload.github.com/alibaba/MNN/tar.gz/024a946b0b8fcf87c8a418229fadd4cd7858ffba
  URL_HASH SHA256=7bf677961508973f113889aa8c99851c7426b29837fbdb10d9c94b6dd58800e6)
set(FETCHCONTENT_UPDATES_DISCONNECTED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
foreach(opt MNN_BUILD_SHARED_LIBS MNN_BUILD_LLM MNN_BUILD_OPENCV MNN_IMGCODECS MNN_LOW_MEMORY MNN_SUPPORT_TRANSFORMER_FUSE MNN_BUILD_FOR_ANDROID_COMMAND)
  set(${opt} ON CACHE BOOL "UnoOne pinned runtime" FORCE)
endforeach()
foreach(opt MNN_SEP_BUILD MNN_BUILD_TOOLS MNN_BUILD_DEMO MNN_LLM_BUILD_DEMO MNN_BUILD_TEST MNN_BUILD_CONVERTER MNN_BUILD_AUDIO MNN_OPENCL MNN_VULKAN MNN_OPENGL MNN_KLEIDIAI MNN_SME2 LLM_SUPPORT_HTTP_RESOURCE)
  set(${opt} OFF CACHE BOOL "UnoOne CPU-only runtime" FORCE)
endforeach()
FetchContent_MakeAvailable(mnn)
add_library(unoone_qwen SHARED qwen_jni.cpp)
target_compile_features(unoone_qwen PRIVATE cxx_std_17)
target_compile_options(unoone_qwen PRIVATE -fexceptions -frtti)
target_include_directories(unoone_qwen PRIVATE ${mnn_SOURCE_DIR}/include ${mnn_SOURCE_DIR}/transformers/llm/engine/include ${mnn_SOURCE_DIR}/tools/cv/include ${mnn_SOURCE_DIR}/3rd_party)
target_link_libraries(unoone_qwen PRIVATE MNN log)
# Android 15+ 16 KB page compatibility (NDK r27).
target_link_options(unoone_qwen PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(MNN PRIVATE "-Wl,-z,max-page-size=16384")

# GUI-Owl Qwen3VL text + real mtmd M-RoPE / DeepStack vision, pinned as a pair.
FetchContent_Declare(owl_llama
  URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/4f5406761517648c23dbd60ea5ade37f77a316c9
  URL_HASH SHA256=bc04be751c6946e51909d1d5591d5be0fd3802673625916a81df788948be06b9)
set(BUILD_SHARED_LIBS ON CACHE BOOL "Shared Android dependencies" FORCE)
foreach(opt GGML_NATIVE GGML_CUDA GGML_METAL GGML_VULKAN GGML_OPENCL GGML_SYCL GGML_BLAS GGML_OPENMP GGML_CPU_KLEIDIAI GGML_BACKEND_DL LLAMA_CURL LLAMA_OPENSSL LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_SERVER LLAMA_BUILD_EXAMPLES LLAMA_BUILD_TOOLS LLAMA_SUBPROCESS MTMD_VIDEO)
  set(${opt} OFF CACHE BOOL "Owl CPU baseline" FORCE)
endforeach()
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "Baseline arm64, no dotprod/i8mm requirements" FORCE)
# Set before subdirectories so ALL native dependencies have 16KB-compatible segments.
add_link_options("-Wl,-z,max-page-size=16384")
set(LLAMA_BUILD_MTMD ON CACHE BOOL "Real multimodal library" FORCE)
FetchContent_MakeAvailable(owl_llama)
add_library(unoone_owl SHARED owl_jni.cpp)
target_compile_features(unoone_owl PRIVATE cxx_std_17)
target_compile_options(unoone_owl PRIVATE -fexceptions -frtti)
target_include_directories(unoone_owl PRIVATE ${owl_llama_SOURCE_DIR}/include ${owl_llama_SOURCE_DIR}/ggml/include ${owl_llama_SOURCE_DIR}/tools/mtmd ${owl_llama_SOURCE_DIR}/vendor/stb)
target_link_libraries(unoone_owl PRIVATE mtmd llama ggml ggml-base ggml-cpu log)
