/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceAssistantIntentRouter")
class VoiceAssistantIntentRouterTest {
    @Test
    fun `ACTION_ASSIST opens voice assistant`() {
        assertTrue(
            VoiceAssistantIntentRouter.opensVoiceAssistant(
                action = Intent.ACTION_ASSIST,
                explicitExtra = false,
            ),
        )
    }

    @Test
    fun `custom voice assistant action opens voice assistant`() {
        assertTrue(
            VoiceAssistantIntentRouter.opensVoiceAssistant(
                action = VoiceAssistantIntentRouter.ACTION_OPEN_VOICE_ASSISTANT,
                explicitExtra = false,
            ),
        )
    }

    @Test
    fun `explicit voice assistant extra opens voice assistant`() {
        assertTrue(
            VoiceAssistantIntentRouter.opensVoiceAssistant(
                action = Intent.ACTION_MAIN,
                explicitExtra = true,
            ),
        )
    }

    @Test
    fun `launcher intent does not open voice assistant`() {
        assertFalse(
            VoiceAssistantIntentRouter.opensVoiceAssistant(
                action = Intent.ACTION_MAIN,
                explicitExtra = false,
            ),
        )
    }
}
