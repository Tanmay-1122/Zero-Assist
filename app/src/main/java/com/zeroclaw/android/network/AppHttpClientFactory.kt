/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.network

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

internal object AppHttpClientFactory {
    fun create(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectionPool(
                ConnectionPool(
                    MAX_IDLE_CONNECTIONS,
                    KEEP_ALIVE_DURATION_SECONDS,
                    TimeUnit.SECONDS,
                ),
            ).connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    internal const val CONNECT_TIMEOUT_SECONDS = 15L
    internal const val READ_TIMEOUT_SECONDS = 15L

    private const val MAX_IDLE_CONNECTIONS = 5
    private const val KEEP_ALIVE_DURATION_SECONDS = 30L
}
