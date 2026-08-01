/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.network

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppHttpClientFactoryTest {
    @Test
    fun createUsesConfiguredTimeouts() {
        val client = AppHttpClientFactory.create()

        assertEquals(
            TimeUnit.SECONDS.toMillis(AppHttpClientFactory.CONNECT_TIMEOUT_SECONDS).toInt(),
            client.connectTimeoutMillis,
        )
        assertEquals(
            TimeUnit.SECONDS.toMillis(AppHttpClientFactory.READ_TIMEOUT_SECONDS).toInt(),
            client.readTimeoutMillis,
        )
    }
}
