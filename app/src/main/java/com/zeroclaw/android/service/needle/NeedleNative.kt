package com.zeroclaw.android.service.needle

/**
 * JNI bindings for `libneedle_jni.so` (see `app/src/main/cpp/needle/`).
 *
 * The engine owns one process-global conversation and is NOT thread-safe;
 * all calls must be serialized with the Mutex in [NeedleEngine]. These
 * bindings perform no locking of their own.
 *
 * Native codes mirror the Needle C API: `>= 0` is success, negative is
 * failure (`-100` = unreadable model file, `-101` = unsupported ABI stub).
 */
internal object NeedleNative {

    @Volatile
    private var loaded: Boolean? = null

    /** Loads `libneedle_jni.so`. Returns false instead of throwing. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary("needle_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        loaded = ok
        return ok
    }

    external fun nativeLoad(modelPath: String): Int

    external fun nativeInit(systemPrompt: String, toolsJson: String): Int

    /** Returns the raw Needle response JSON, or null on engine failure. */
    external fun nativeComplete(input: String, maxNewTokens: Int): String?

    external fun nativeReset()
}
