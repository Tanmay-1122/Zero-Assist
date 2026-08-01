/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.FfiAuthProfile
import com.zeroclaw.ffi.FfiException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("AuthProfilesBridge")
@OptIn(ExperimentalCoroutinesApi::class)
class AuthProfilesBridgeTest {
    private lateinit var bridge: AuthProfilesBridge

    @BeforeEach
    fun setUp() {
        mockkStatic("com.zeroclaw.ffi.Zeroclaw_androidKt")
        bridge = AuthProfilesBridge(ioDispatcher = UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("listProfiles maps FFI records")
    fun `listProfiles maps FFI records`() =
        runTest {
            every { com.zeroclaw.ffi.listAuthProfiles() } returns
                listOf(
                    FfiAuthProfile(
                        id = "openai-codex:default",
                        provider = "openai-codex",
                        profileName = "default",
                        kind = "oauth",
                        isActive = true,
                        expiresAtMs = 1_800_000_000_000L,
                        createdAtMs = 1_700_000_000_000L,
                        updatedAtMs = 1_700_000_000_500L,
                    ),
                )

            val profiles = bridge.listProfiles()

            assertEquals(1, profiles.size)
            assertEquals("openai-codex:default", profiles.single().id)
            assertEquals("openai-codex", profiles.single().provider)
            assertEquals("default", profiles.single().profileName)
            assertEquals("oauth", profiles.single().kind)
            assertEquals(true, profiles.single().isActive)
            assertEquals(1_800_000_000_000L, profiles.single().expiresAtMs)
        }

    @Test
    @DisplayName("listProfiles propagates FfiException")
    fun `listProfiles propagates FfiException`() =
        runTest {
            every {
                com.zeroclaw.ffi.listAuthProfiles()
            } throws FfiException.StateException("auth unavailable")

            assertThrows<FfiException> {
                bridge.listProfiles()
            }
        }

    @Test
    @DisplayName("removeProfile delegates to FFI")
    fun `removeProfile delegates to FFI`() =
        runTest {
            every { com.zeroclaw.ffi.removeAuthProfile("openai-codex", "default") } returns Unit

            bridge.removeProfile("openai-codex", "default")

            verify(exactly = 1) {
                com.zeroclaw.ffi.removeAuthProfile("openai-codex", "default")
            }
        }
}
