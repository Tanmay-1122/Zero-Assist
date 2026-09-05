package com.zeroclaw.android.service.needle

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Singleton owner of the Needle 2 native session (see [NeedleNative]).
 *
 * Lifecycle: [load] once (model bytes → engine), [initialize] once per
 * tools-schema version (system prompt + tools are pinned as KV sinks),
 * [complete] per planner step, [reset] at each new goal. There is no engine
 * unload; the ~28MB session stays resident once loaded.
 *
 * All native calls are serialized with [mutex] because the engine owns one
 * process-global conversation. Instantiate once per process via
 * `ZeroClawApplication.needleEngine`, never per request.
 */
class NeedleEngine(appContext: Context) {

    private val appContext: Context = appContext.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    @Volatile
    private var initialized = false

    fun isReady(): Boolean = loaded && initialized

    /**
     * Loads model bytes into the engine. Safe to call repeatedly; subsequent
     * calls are no-ops once loaded.
     */
    suspend fun load(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withContext true
            if (!NeedleNative.ensureLoaded()) {
                Log.w(TAG, "libneedle_jni.so not loadable on this device")
                return@withContext false
            }
            val rc = try {
                NeedleNative.nativeLoad(modelFile.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "nativeLoad threw: ${e.message}")
                return@withContext false
            }
            loaded = rc >= 0
            if (!loaded) Log.w(TAG, "nativeLoad failed rc=$rc")
            loaded
        }
    }

    /** Pins system prompt + tool schema. Re-runs only when [toolsJson] changes. */
    suspend fun initialize(systemPrompt: String, toolsJson: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!loaded) return@withContext false
                val rc = try {
                    NeedleNative.nativeInit(systemPrompt, toolsJson)
                } catch (e: Exception) {
                    Log.w(TAG, "nativeInit threw: ${e.message}")
                    return@withContext false
                }
                initialized = rc >= 0
                if (!initialized) Log.w(TAG, "nativeInit failed rc=$rc")
                initialized
            }
        }

    /**
     * Runs one engine turn. Returns the raw response JSON, or null on engine
     * failure. No timeout is applied here; callers (planners) own the cap so
     * it covers mutex queueing plus inference.
     */
    suspend fun complete(input: String, maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!isReady()) return@withContext null
                try {
                    NeedleNative.nativeComplete(input, maxNewTokens)
                } catch (e: Exception) {
                    Log.w(TAG, "nativeComplete threw: ${e.message}")
                    null
                }
            }
        }

    /** Rewinds the conversation, keeping tools loaded. Call at each new goal. */
    suspend fun reset() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) return@withContext
            try {
                NeedleNative.nativeReset()
            } catch (e: Exception) {
                Log.w(TAG, "nativeReset threw: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "NeedleEngine"
        const val DEFAULT_MAX_NEW_TOKENS = 128

        /**
         * v1 engine support: real Needle builds ship for arm64-v8a only.
         * armv7/riscv64 folders exist upstream and can be added later.
         */
        fun isDeviceSupported(): Boolean =
            Build.SUPPORTED_ABIS.contains("arm64-v8a")
    }
}
