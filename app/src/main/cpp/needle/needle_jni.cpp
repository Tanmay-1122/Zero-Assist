// Copyright 2026 Zero-Assist Community
//
// Licensed under the MIT License. See LICENSE in the project root.
//
// JNI bridge for the Needle 2 native engine (libneedle.a).
//
// Threading: the Needle engine owns one process-global conversation and is
// NOT thread-safe. All calls must be serialized on the Kotlin side with the
// single Mutex owned by NeedleEngine. These JNI entry points perform no
// locking of their own.
//
// Return codes: needle_* return >= 0 on success, negative on failure.
// The JNI layer passes codes through unchanged; policy (fallback to cloud)
// lives in NeedleDeviceControlPlanner / NeedleFirstPlanner.

#include <jni.h>

#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "needle.h"

#define LOG_TAG "NeedleJNI"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#if NEEDLE_SUPPORTED

namespace {

std::vector<unsigned char> readFile(const char* path) {
    std::vector<unsigned char> data;
    FILE* f = std::fopen(path, "rb");
    if (!f) return data;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (size > 0) {
        data.resize(static_cast<size_t>(size));
        const size_t got = std::fread(data.data(), 1, data.size(), f);
        data.resize(got);
    }
    std::fclose(f);
    return data;
}

std::string jstringToUtf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_zeroclaw_android_service_needle_NeedleNative_nativeLoad(
    JNIEnv* env, jclass, jstring modelPath) {
    const std::string path = jstringToUtf8(env, modelPath);
    const std::vector<unsigned char> data = readFile(path.c_str());
    if (data.empty()) {
        LOGW("nativeLoad: cannot read model at %s", path.c_str());
        return -100;
    }
    const int rc = needle_load(
        data.data(), static_cast<unsigned long long>(data.size()));
    if (rc < 0) LOGW("nativeLoad: needle_load failed rc=%d", rc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_zeroclaw_android_service_needle_NeedleNative_nativeInit(
    JNIEnv* env, jclass, jstring systemPrompt, jstring toolsJson) {
    const std::string system = jstringToUtf8(env, systemPrompt);
    const std::string tools = jstringToUtf8(env, toolsJson);
    const int rc = needle_init(
        system.empty() ? nullptr : system.c_str(),
        tools.empty() ? nullptr : tools.c_str(),
        nullptr);
    if (rc < 0) LOGW("nativeInit: needle_init failed rc=%d", rc);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_zeroclaw_android_service_needle_NeedleNative_nativeComplete(
    JNIEnv* env, jclass, jstring input, jint maxNewTokens) {
    const std::string query = jstringToUtf8(env, input);
    // Needle responses are single small JSON objects; 16 KiB is ample.
    constexpr int kOutCapacity = 16 * 1024;
    std::vector<char> out(static_cast<size_t>(kOutCapacity), '\0');
    const int rc = needle_complete(
        query.c_str(), static_cast<int>(maxNewTokens), out.data(), kOutCapacity);
    if (rc < 0) {
        LOGW("nativeComplete: needle_complete failed rc=%d", rc);
        return nullptr;
    }
    out.back() = '\0';
    return env->NewStringUTF(out.data());
}

JNIEXPORT void JNICALL
Java_com_zeroclaw_android_service_needle_NeedleNative_nativeReset(JNIEnv*, jclass) {
    needle_reset();
}

}  // extern "C"

#else  // !NEEDLE_SUPPORTED

// Stub for ABIs without a Needle engine build (anything but arm64-v8a in v1).
// Every call fails fast; the Kotlin readiness gate (Build.SUPPORTED_ABIS)
// keeps these unreachable on unsupported devices.
extern "C" {

JNIEXPORT jint JNICALL
Java_com_zeroclaw_android_service_needle_NeedleNative_nativeLoad(
    JNIEnv*, jclass, jstring) {
    return -101;
}

JNIEXPORT jint JNICALL
Java_com_zeroclaw_android_service_needle_NeedleNative_nativeInit(
    JNIEnv*, jclass, jstring, jstring) {
    return -101;
}

JNIEXPORT jstring JNICALL
Java_com_zeroclaw_android_service_needle_NeedleNative_nativeComplete(
    JNIEnv*, jclass, jstring, jint) {
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_zeroclaw_android_service_needle_NeedleNative_nativeReset(JNIEnv*, jclass) {}

}  // extern "C"

#endif  // NEEDLE_SUPPORTED
