/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.ui.screen.settings.TestSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfficialPluginSettingsTest {
    @Test
    fun `Composio official plugin switch persists to AppSettings`() =
        runTest {
            val repository = TestSettingsRepository()

            val handled = repository.setOfficialPluginEnabled(OfficialPlugins.COMPOSIO, true)

            assertTrue(handled)
            assertTrue(repository.settings.first().composioEnabled)
        }

    @Test
    fun `all configurable official plugin switches are handled`() =
        runTest {
            val repository = TestSettingsRepository()

            for (pluginId in OfficialPlugins.ALL) {
                assertTrue(repository.setOfficialPluginEnabled(pluginId, true), pluginId)
            }

            val settings = repository.settings.first()
            assertTrue(settings.webSearchEnabled)
            assertTrue(settings.webFetchEnabled)
            assertTrue(settings.httpRequestEnabled)
            assertTrue(settings.browserEnabled)
            assertTrue(settings.composioEnabled)
            assertTrue(settings.sharedFolderEnabled)
            assertTrue(settings.workflowFolderEnabled)
        }

}
