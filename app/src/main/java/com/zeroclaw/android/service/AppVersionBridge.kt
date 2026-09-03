/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.getVersion

internal object AppVersionBridge {
    const val UNKNOWN_VERSION = "unknown"

    @Suppress("TooGenericExceptionCaught")
    fun crateVersionOrFallback(versionProvider: () -> String = ::getVersion): String =
        try {
            versionProvider()
        } catch (_: Exception) {
            UNKNOWN_VERSION
        }
}
