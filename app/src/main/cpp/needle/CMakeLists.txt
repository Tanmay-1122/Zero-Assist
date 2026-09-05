# Copyright 2026 Zero-Assist Community
#
# Licensed under the MIT License. See LICENSE in the project root.
#
# Builds libneedle_jni.so: a thin JNI wrapper around the prebuilt Needle 2
# engine (libneedle.a, vendored per-ABI under libs/).
#
# Only arm64-v8a ships a real engine in v1. All other ABIs compile
# a stub that fails fast; the Kotlin readiness gate keeps those unreachable.

cmake_minimum_required(VERSION 3.22.1)

project(needle_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(needle_jni SHARED needle_jni.cpp)

target_include_directories(needle_jni PRIVATE include)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(needle STATIC IMPORTED)
    set_target_properties(
        needle
        PROPERTIES
            IMPORTED_LOCATION
                ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libneedle.a
    )
    target_link_libraries(needle_jni needle log)
    target_compile_definitions(needle_jni PRIVATE NEEDLE_SUPPORTED=1)
else()
    find_library(log-lib log)
    target_link_libraries(needle_jni ${log-lib})
    target_compile_definitions(needle_jni PRIVATE NEEDLE_SUPPORTED=0)
endif()
