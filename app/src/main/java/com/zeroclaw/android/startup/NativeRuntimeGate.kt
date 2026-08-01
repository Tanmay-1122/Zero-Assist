/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.startup

import android.content.Context
import android.util.Log
import com.zeroclaw.android.BuildConfig
import com.zeroclaw.android.service.AppVersionBridge
import com.zeroclaw.android.service.ExternalZeroClawConfig

internal class NativeRuntimeGate(
    private val loadLibrary: (String) -> Unit = System::loadLibrary,
    private val crateVersionProvider: () -> String = AppVersionBridge::crateVersionOrFallback,
    private val appVersionProvider: () -> String = { BuildConfig.VERSION_NAME },
    private val configOverlayInstaller: (Context) -> Unit = ExternalZeroClawConfig::installBundledOverlay,
    private val logWarning: (String) -> Unit = { message -> Log.w(TAG, message) },
    private val logError: (String, Throwable?) -> Unit = { message, throwable ->
        if (throwable == null) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, message, throwable)
        }
    },
) {
    fun loadLibraries() {
        loadLibrary(SQLCIPHER_LIBRARY)
        try {
            loadLibrary(ZEROCLAW_LIBRARY)
        } catch (e: UnsatisfiedLinkError) {
            logError(MISSING_NATIVE_LOG_MESSAGE, e)
            throw RuntimeException(MISSING_NATIVE_CRASH_MESSAGE, e)
        }
    }

    fun verifyCrateVersion() {
        try {
            val crateVersion = crateVersionProvider()
            val appVersion = appVersionProvider()
            if (crateVersion != appVersion) {
                logWarning("Crate/app version mismatch: native=$crateVersion, app=$appVersion")
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            logError("Failed to verify crate version: ${e.message}", null)
        }
    }

    fun installBundledConfigOverlay(context: Context) {
        configOverlayInstaller(context)
    }

    private companion object {
        private const val TAG = "NativeRuntimeGate"
        private const val SQLCIPHER_LIBRARY = "sqlcipher"
        private const val ZEROCLAW_LIBRARY = "zeroclaw"
        private const val MISSING_NATIVE_LOG_MESSAGE = "Missing native library libzeroclaw.so"
        private const val MISSING_NATIVE_CRASH_MESSAGE =
            "libzeroclaw.so not found. Ensure :lib is built and the APK includes the correct ABI split."
    }
}
