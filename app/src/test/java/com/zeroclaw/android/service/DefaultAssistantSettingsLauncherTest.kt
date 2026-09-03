/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefaultAssistantSettingsLauncher")
class DefaultAssistantSettingsLauncherTest {
    @Test
    fun `fallback actions start at assistant settings and end at system settings`() {
        assertEquals(
            "android.settings.VOICE_INPUT_SETTINGS",
            DefaultAssistantSettingsLauncher.fallbackActions.first(),
        )
        assertEquals(
            "android.settings.SETTINGS",
            DefaultAssistantSettingsLauncher.fallbackActions.last(),
        )
    }

    @Test
    fun `fallback actions include default apps settings`() {
        assertTrue(
            DefaultAssistantSettingsLauncher.fallbackActions.contains(
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
            ),
        )
    }
}
