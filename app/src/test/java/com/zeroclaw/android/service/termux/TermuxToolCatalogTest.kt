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

@DisplayName("TermuxToolCatalog")
class TermuxToolCatalogTest {
    @Test
    fun `lists inactive Termux bridge tools when runtime is not ready`() =
        runTest {
            val catalog =
                TermuxToolCatalog(
                    StaticStatusProvider(
                        ready = false,
                        health =
                            TermuxHealthSnapshot(
                                status = TermuxHealthStatus.UNAVAILABLE,
                                reason = "Termux command execution is not implemented in this Android build yet.",
                            ),
                    ),
                )

            val tools = catalog.listTools()

            assertEquals(listOf("termux_get_capabilities", "termux_run"), tools.map { it.name })
            tools.forEach { tool ->
                assertEquals("termux", tool.source)
                assertFalse(tool.isActive)
                assertEquals(
                    "Termux command execution is not implemented in this Android build yet.",
                    tool.inactiveReason,
                )
            }
            assertTrue(tools[0].parametersJson.contains("\"refresh\""))
            assertTrue(tools[1].parametersJson.contains("\"command\""))
            assertTrue(tools[1].parametersJson.contains("\"timeout_seconds\""))
        }

    @Test
    fun `lists active Termux bridge metadata when runtime status is ready`() =
        runTest {
            val catalog =
                TermuxToolCatalog(
                    StaticStatusProvider(
                        ready = true,
                        health =
                            TermuxHealthSnapshot(
                                status = TermuxHealthStatus.READY,
                                reason = "ready",
                            ),
                    ),
                )

            val tools = catalog.listTools()

            assertEquals(listOf("termux_get_capabilities", "termux_run"), tools.map { it.name })
            tools.forEach { tool ->
                assertTrue(tool.isActive)
                assertEquals("", tool.inactiveReason)
            }
        }

    private class StaticStatusProvider(
        private val ready: Boolean,
        private val health: TermuxHealthSnapshot,
    ) : TermuxRuntimeStatusProvider {
        override suspend fun currentStatus(): TermuxRuntimeStatus =
            TermuxRuntimeStatus(
                packageState =
                    TermuxPackageState(
                        availability = TermuxPackageAvailability.INSTALLED,
                    ),
                permissionState =
                    TermuxPermissionState(
                        availability = TermuxPermissionAvailability.GRANTED,
                    ),
                bootstrapState =
                    TermuxBootstrapState(
                        availability = TermuxBootstrapAvailability.AVAILABLE,
                    ),
                health = health,
            )
    }
}
