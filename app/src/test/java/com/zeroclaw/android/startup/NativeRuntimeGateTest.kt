/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.startup

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NativeRuntimeGateTest {
    @Test
    fun `loadLibraries loads sqlcipher before zeroclaw`() {
        val loadedLibraries = mutableListOf<String>()
        val gate =
            NativeRuntimeGate(
                loadLibrary = loadedLibraries::add,
            )

        gate.loadLibraries()

        assertEquals(listOf("sqlcipher", "zeroclaw"), loadedLibraries)
    }

    @Test
    fun `loadLibraries fails fast when zeroclaw library is missing`() {
        val errors = mutableListOf<Pair<String, Throwable?>>()
        val cause = UnsatisfiedLinkError("missing")
        val gate =
            NativeRuntimeGate(
                loadLibrary = { libraryName ->
                    if (libraryName == "zeroclaw") {
                        throw cause
                    }
                },
                logError = { message, throwable -> errors += message to throwable },
            )

        val error =
            assertThrows<RuntimeException> {
                gate.loadLibraries()
            }

        assertSame(cause, error.cause)
        assertTrue(error.message.orEmpty().contains("libzeroclaw.so not found"))
        assertEquals("Missing native library libzeroclaw.so", errors.single().first)
        assertSame(cause, errors.single().second)
    }

    @Test
    fun `verifyCrateVersion logs mismatch without failing startup`() {
        val warnings = mutableListOf<String>()
        val gate =
            NativeRuntimeGate(
                crateVersionProvider = { "0.0.1" },
                appVersionProvider = { "0.0.2" },
                logWarning = warnings::add,
            )

        gate.verifyCrateVersion()

        assertEquals(
            listOf("Crate/app version mismatch: native=0.0.1, app=0.0.2"),
            warnings,
        )
    }

    @Test
    fun `verifyCrateVersion preserves interruption`() {
        val interrupted = InterruptedException("stop")
        val gate =
            NativeRuntimeGate(
                crateVersionProvider = { throw interrupted },
            )

        val error =
            assertThrows<InterruptedException> {
                gate.verifyCrateVersion()
            }

        assertSame(interrupted, error)
    }

    @Test
    fun `installBundledConfigOverlay delegates to installer`() {
        val context = mockk<Context>()
        var installedContext: Context? = null
        val gate =
            NativeRuntimeGate(
                configOverlayInstaller = { installedContext = it },
            )

        gate.installBundledConfigOverlay(context)

        assertSame(context, installedContext)
    }
}
