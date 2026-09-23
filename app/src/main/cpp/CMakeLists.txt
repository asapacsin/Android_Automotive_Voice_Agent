cmake_minimum_required(VERSION 3.22.1)

project(nova_aec C CXX)
set(CMAKE_CXX_STANDARD 20)

set(WEBRTC_AEC3_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc-aec3)
set(C_API_DIR ${CMAKE_CURRENT_SOURCE_DIR}/c_api)

add_library(webrtc_aec3 STATIC)

target_compile_options(webrtc_aec3 PRIVATE -Wall -fPIC -Wno-deprecated -fexceptions)
target_compile_definitions(webrtc_aec3 PRIVATE WEBRTC_ANDROID WEBRTC_POSIX)

file(GLOB_RECURSE AEC3_SRC
    ${WEBRTC_AEC3_DIR}/src/**/*.cc
    ${WEBRTC_AEC3_DIR}/src/**/*.c
    ${WEBRTC_AEC3_DIR}/src/**/*.cpp
)

list(FILTER AEC3_SRC EXCLUDE REGEX ".*_avx2\\.cc$")
list(FILTER AEC3_SRC EXCLUDE REGEX ".*_sse2\\.cc$")
list(FILTER AEC3_SRC EXCLUDE REGEX ".*_sse\\.cc$")
list(FILTER AEC3_SRC EXCLUDE REGEX "cpu_features_linux\\.c$")
list(FILTER AEC3_SRC EXCLUDE REGEX "cpu_features_android\\.c$")

if(NOT (ANDROID_ABI MATCHES "arm64-v8a|armeabi-v7a"))
    list(FILTER AEC3_SRC EXCLUDE REGEX ".*_neon\\.cc$")
endif()

target_sources(webrtc_aec3 PRIVATE ${AEC3_SRC})
target_sources(webrtc_aec3 PRIVATE
    ${C_API_DIR}/webrtc_aec3_c.cc
    ${C_API_DIR}/webrtc_aec3_c.h
)

target_include_directories(webrtc_aec3 PUBLIC
    ${WEBRTC_AEC3_DIR}/src/
    ${WEBRTC_AEC3_DIR}/src/api
    ${WEBRTC_AEC3_DIR}/src/base/rtc_base
    ${WEBRTC_AEC3_DIR}/src/base
    ${WEBRTC_AEC3_DIR}/src/base/system_wrappers/include
    ${WEBRTC_AEC3_DIR}/src/audio_processing
    ${WEBRTC_AEC3_DIR}/src/absl
    ${WEBRTC_AEC3_DIR}/src/base/jsoncpp/include
    ${WEBRTC_AEC3_DIR}/src/audio_processing/aec3/
    ${WEBRTC_AEC3_DIR}/src/audio_processing/resampler
    ${WEBRTC_AEC3_DIR}/src/audio_processing/logging
    ${WEBRTC_AEC3_DIR}/src/audio_processing/include
    ${WEBRTC_AEC3_DIR}/src/audio_processing/utility
    ${WEBRTC_AEC3_DIR}/src/rtc_base/experiments
    ${WEBRTC_AEC3_DIR}/src/common_audio
    ${WEBRTC_AEC3_DIR}/src/common_audio/third_party/ooura/fft_size_128
    ${C_API_DIR}
)

set_target_properties(webrtc_aec3 PROPERTIES LINKER_LANGUAGE CXX)

add_library(nova_aec SHARED
    nova_aec.cpp
    nova_aec_jni.cpp
)

find_library(log-lib log)

target_include_directories(nova_aec PRIVATE
    ${C_API_DIR}
    ${WEBRTC_AEC3_DIR}/src/
    ${WEBRTC_AEC3_DIR}/src/audio_processing
    ${WEBRTC_AEC3_DIR}/src/audio_processing/resampler
    ${WEBRTC_AEC3_DIR}/src/common_audio
    ${WEBRTC_AEC3_DIR}/src/base/rtc_base
)
target_link_libraries(nova_aec PRIVATE webrtc_aec3 ${log-lib})
target_link_options(nova_aec PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
