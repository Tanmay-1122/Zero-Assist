/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.startup

import android.util.Log
import androidx.work.Configuration

internal object AppWorkManagerConfigurationFactory {
    fun create(debug: Boolean): Configuration =
        Configuration
            .Builder()
            .setMinimumLoggingLevel(
                if (debug) {
                    Log.DEBUG
                } else {
                    Log.ERROR
                },
            ).build()
}
