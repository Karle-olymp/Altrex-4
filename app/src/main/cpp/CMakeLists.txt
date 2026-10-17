cmake_minimum_required(VERSION 3.10)

project("llamacppkt")

set(CMAKE_CXX_STANDARD 17)

# [ALTREX] llama.rn wrappers (llamarn/) + llama.cpp b11385 and codec.cpp in their upstream layout
# (vendor/). Source lists follow llama.rn's cmake/rnllama-sources.cmake (CPU-only build).
set(RNLLAMA_CPP_DIR       ${CMAKE_SOURCE_DIR}/llamarn)
set(RNLLAMA_LLAMA_CPP_DIR ${CMAKE_SOURCE_DIR}/vendor/llama.cpp)
set(RNLLAMA_CODEC_CPP_DIR ${CMAKE_SOURCE_DIR}/vendor/codec.cpp)

set(_ggml   ${RNLLAMA_LLAMA_CPP_DIR}/ggml/src)
set(_llama  ${RNLLAMA_LLAMA_CPP_DIR}/src)
set(_common ${RNLLAMA_LLAMA_CPP_DIR}/common)
set(_mtmd   ${RNLLAMA_LLAMA_CPP_DIR}/tools/mtmd)
set(_vendor ${RNLLAMA_LLAMA_CPP_DIR}/vendor)
set(_codec  ${RNLLAMA_CODEC_CPP_DIR})

# Order matters where basenames collide (see llama.rn's rnllama-sources.cmake).
include_directories(
    ${RNLLAMA_CPP_DIR}
    ${RNLLAMA_LLAMA_CPP_DIR}
    ${RNLLAMA_LLAMA_CPP_DIR}/include
    ${RNLLAMA_LLAMA_CPP_DIR}/ggml/include
    ${_ggml}
    ${_common}
    ${_llama}
    ${_ggml}/ggml-cpu
    ${_mtmd}
    ${_vendor}
    ${_vendor}/hash
    ${_codec}/include
    ${_codec}/common
)

file(GLOB GGML_CPU_SOURCES
    ${_ggml}/ggml-cpu/*.c
    ${_ggml}/ggml-cpu/*.cpp
    ${_ggml}/ggml-cpu/amx/*.cpp
    ${_ggml}/ggml-cpu/tiled/*.cpp
)
list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX "/hbm\\.cpp$")

file(GLOB LLAMA_SOURCES ${_llama}/*.cpp ${_llama}/models/*.cpp)
list(FILTER LLAMA_SOURCES EXCLUDE REGEX "/llama-quant\\.cpp$")

file(GLOB COMMON_SOURCES ${_common}/*.cpp ${_common}/jinja/*.cpp ${_common}/parsers/*.cpp)
list(FILTER COMMON_SOURCES EXCLUDE REGEX
    "/(arg|console|debug|download|hf-cache|imatrix-loader|llguidance|preset|subproc)\\.cpp$")

file(GLOB MTMD_SOURCES ${_mtmd}/*.cpp ${_mtmd}/models/*.cpp)

file(GLOB CODEC_SOURCES
    ${_codec}/src/*.cpp
    ${_codec}/src/batch/*.cpp
    ${_codec}/src/lm/*.cpp
    ${_codec}/src/models/*.cpp
    ${_codec}/src/ops/*.cpp
    ${_codec}/src/runtime/*.cpp
    ${_codec}/common/audio_lm.cpp
)

set(
    SOURCE_FILES
    ${_ggml}/ggml.c
    ${_ggml}/ggml-alloc.c
    ${_ggml}/ggml-backend.cpp
    ${_ggml}/ggml-backend-dl.cpp
    ${_ggml}/ggml-backend-meta.cpp
    ${_ggml}/ggml-backend-reg.cpp
    ${_ggml}/ggml-opt.cpp
    ${_ggml}/ggml-threading.cpp
    ${_ggml}/ggml-quants.c
    ${_ggml}/gguf.cpp
    ${GGML_CPU_SOURCES}
    ${LLAMA_SOURCES}
    ${COMMON_SOURCES}
    ${MTMD_SOURCES}
    ${_vendor}/hash/hash.cpp
    ${_vendor}/hash/sha256/sha256.c
    ${CODEC_SOURCES}
    ${RNLLAMA_CPP_DIR}/anyascii.c
    ${RNLLAMA_CPP_DIR}/rn-llama.cpp
    ${RNLLAMA_CPP_DIR}/rn-completion.cpp
    ${RNLLAMA_CPP_DIR}/rn-decision.cpp
    ${RNLLAMA_CPP_DIR}/rn-slot.cpp
    ${RNLLAMA_CPP_DIR}/rn-slot-manager.cpp
    ${RNLLAMA_CPP_DIR}/rn-tts.cpp

    # Our JNI wrapper
    ${CMAKE_SOURCE_DIR}/jni.cpp
)

find_library(LOG_LIB log)

function(build_library target_name arch cpu_flags)
    if (NOT ${arch} STREQUAL "generic")
        set(SOURCE_FILES_ARCH
            ${_ggml}/ggml-cpu/arch/${arch}/quants.c
            ${_ggml}/ggml-cpu/arch/${arch}/repack.cpp
        )
    endif ()

    add_library(
            ${target_name}
            SHARED
            ${SOURCE_FILES}
            ${SOURCE_FILES_ARCH}
    )

    target_link_libraries(${target_name} ${LOG_LIB} android)

    if (${arch} STREQUAL "generic")
        target_compile_options(${target_name} PRIVATE -DGGML_CPU_GENERIC)
    endif ()

    # ThinLTO (as llama.rn): full LTO of six variants can run out of memory.
    target_compile_options(${target_name} PRIVATE
        -DGGML_USE_CPU
        -DGGML_USE_CPU_REPACK
        -pthread
        ${cpu_flags}
        -fvectorize
        -ffp-model=fast
        -fno-finite-math-only
        -flto=thin
        -D_GNU_SOURCE
    )

    if (${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -DRNLLAMA_ANDROID_ENABLE_LOGGING)
    endif ()

    target_compile_options(${target_name} PRIVATE -O3 -DNDEBUG)
    target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

    target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
    target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
    target_link_options(${target_name} PRIVATE -flto=thin)
endfunction()


# Default target
build_library("rnllama" "generic" "")


if (ANDROID_ABI STREQUAL "arm64-v8a")
    build_library("rnllama_v8" "arm" "-march=armv8-a")
    build_library("rnllama_v8_2" "arm" "-march=armv8.2-a")
    build_library("rnllama_v8_2_dotprod" "arm" "-march=armv8.2-a+dotprod")
    build_library("rnllama_v8_2_i8mm" "arm" "-march=armv8.2-a+i8mm")
    build_library("rnllama_v8_2_dotprod_i8mm" "arm" "-march=armv8.2-a+dotprod+i8mm")
endif()
