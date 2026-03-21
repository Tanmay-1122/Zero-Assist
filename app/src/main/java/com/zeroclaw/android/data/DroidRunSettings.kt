/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

/**
 * Shared preference keys for the global DroidRun server settings.
 */
object DroidRunSettings {
    /** SharedPreferences file name for DroidRun configuration storage. */
    const val PREFS_NAME = "droidrun_config"
    /** SharedPreferences key for the DroidRun server URL. */
    const val KEY_SERVER_URL = "server_url"
    /** SharedPreferences key for the DroidRun API key. */
    const val KEY_API_KEY = "api_key"
}
