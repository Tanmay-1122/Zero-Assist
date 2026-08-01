/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.android.service.AppOwnedToolCatalog
import com.zeroclaw.android.service.CompositeAppOwnedToolCatalog
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CompositeAppOwnedToolCatalog")
class CompositeAppOwnedToolCatalogTest {
    @Test
    fun `concatenates catalogs in declaration order`() =
        runTest {
            val catalog =
                CompositeAppOwnedToolCatalog(
                    SingleToolCatalog("shell"),
                    SingleToolCatalog("termux_run"),
                )

            val tools = catalog.listTools()

            assertEquals(listOf("shell", "termux_run"), tools.map { it.name })
        }

    private class SingleToolCatalog(
        private val name: String,
    ) : AppOwnedToolCatalog {
        override suspend fun listTools(): List<ToolSpec> =
            listOf(
                ToolSpec(
                    name = name,
                    description = "$name description",
                    source = "test",
                    parametersJson = "{}",
                    isActive = false,
                    inactiveReason = "inactive",
                ),
            )
    }
}
