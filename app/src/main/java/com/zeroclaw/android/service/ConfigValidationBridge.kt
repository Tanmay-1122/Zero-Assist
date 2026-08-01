/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.validateConfig

/**
 * App-owned boundary for validating TOML through ZeroClaw Master.
 */
fun interface ConfigValidationBridge {
    /**
     * Returns an empty string when [configToml] is valid, otherwise a
     * human-readable validation error.
     */
    fun validate(configToml: String): String
}

/**
 * Production bridge backed by the generated UniFFI binding.
 */
object NativeConfigValidationBridge : ConfigValidationBridge {
    override fun validate(configToml: String): String = validateConfig(configToml)
}
