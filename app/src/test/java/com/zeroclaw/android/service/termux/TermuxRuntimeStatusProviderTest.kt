/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefaultTermuxRuntimeStatusProvider")
class TermuxRuntimeStatusProviderTest {
    @Test
    fun `does not call health client until package and permission are ready`() =
        runTest {
            val healthClient = CountingHealthClient()
            val provider =
                DefaultTermuxRuntimeStatusProvider(
                    probe =
                        FakeTermuxRuntimeProbe(
                            packageAvailability = TermuxPackageAvailability.NOT_INSTALLED,
                            permissionAvailability = TermuxPermissionAvailability.GRANTED,
                        ),
                    healthClient = healthClient,
                )

            val status = provider.currentStatus()

            assertFalse(status.isReady)
            assertEquals(0, healthClient.calls)
            assertEquals("Install Termux before enabling local runtime tools.", status.inactiveReason())
        }

    @Test
    fun `ready only when package permission and health are ready`() =
        runTest {
            val provider =
                DefaultTermuxRuntimeStatusProvider(
                    probe =
                        FakeTermuxRuntimeProbe(
                            packageAvailability = TermuxPackageAvailability.INSTALLED,
                            permissionAvailability = TermuxPermissionAvailability.GRANTED,
                        ),
                    healthClient =
                        StaticHealthClient(
                            TermuxHealthSnapshot(
                                status = TermuxHealthStatus.READY,
                                reason = "Termux runtime ready.",
                            ),
                        ),
                )

            val status = provider.currentStatus()

            assertTrue(status.isReady)
            assertEquals("", status.inactiveReason())
        }

    @Test
    fun `permission denial takes precedence over health probing`() =
        runTest {
            val healthClient = CountingHealthClient()
            val provider =
                DefaultTermuxRuntimeStatusProvider(
                    probe =
                        FakeTermuxRuntimeProbe(
                            packageAvailability = TermuxPackageAvailability.INSTALLED,
                            permissionAvailability = TermuxPermissionAvailability.DENIED,
                        ),
                    healthClient = healthClient,
                )

            val status = provider.currentStatus()

            assertFalse(status.isReady)
            assertEquals(0, healthClient.calls)
            assertEquals(
                "Grant Zero-Assist the Termux RUN_COMMAND permission in Android app settings.",
                status.inactiveReason(),
            )
        }

    private class FakeTermuxRuntimeProbe(
        private val packageAvailability: TermuxPackageAvailability,
        private val permissionAvailability: TermuxPermissionAvailability,
    ) : TermuxRuntimeProbe {
        override fun snapshot(): TermuxRuntimeProbeSnapshot =
            TermuxRuntimeProbeSnapshot(
                packageState = TermuxPackageState(availability = packageAvailability),
                permissionState = TermuxPermissionState(availability = permissionAvailability),
                bootstrapState =
                    TermuxBootstrapState(
                        availability = TermuxBootstrapAvailability.AVAILABLE,
                        intentSpec = TermuxIntentBoundary().bootstrapIntent(),
                    ),
            )
    }

    private class StaticHealthClient(
        private val snapshot: TermuxHealthSnapshot,
    ) : TermuxHealthClient {
        override suspend fun checkHealth(): TermuxHealthSnapshot = snapshot
    }

    private class CountingHealthClient : TermuxHealthClient {
        var calls = 0

        override suspend fun checkHealth(): TermuxHealthSnapshot {
            calls += 1
            return TermuxHealthSnapshot(
                status = TermuxHealthStatus.READY,
                reason = "ready",
            )
        }
    }
}

