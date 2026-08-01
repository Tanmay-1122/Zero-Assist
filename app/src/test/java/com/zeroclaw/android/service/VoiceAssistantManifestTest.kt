/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Voice assistant manifest")
class VoiceAssistantManifestTest {
    @Test
    fun `dedicated assistant activity declares default assist entrypoint without claiming home`() {
        val manifest = androidManifestText()

        assertTrue(manifest.contains("android:name=\".VoiceAssistantActivity\""))
        assertTrue(manifest.contains("@style/Theme.ZeroAssist.Assistant"))
        assertTrue(manifest.contains("android:taskAffinity=\"com.zeroclaw.android.assistant\""))
        assertTrue(manifest.contains("android.intent.action.ASSIST"))
        assertTrue(manifest.contains("android.intent.category.DEFAULT"))
        assertTrue(manifest.contains(VoiceAssistantIntentRouter.ACTION_OPEN_VOICE_ASSISTANT))
        assertFalse(manifest.contains("android.service.voice.VoiceInteractionService"))
        assertFalse(manifest.contains("android:resource=\"@xml/zero_assist_voice_interaction\""))
        assertFalse(manifest.contains("android:name=\".service.ZeroAssistVoiceInteractionSessionService\""))
        assertFalse(manifest.contains("android:name=\".service.ZeroAssistRecognitionService\""))
        assertFalse(manifest.contains("android.speech.RecognitionService"))
        assertFalse(manifest.contains("android:resource=\"@xml/zero_assist_recognition\""))
        assertFalse(manifest.contains("android.intent.category.HOME"))
        assertFalse(manifest.contains("android.permission.CALL_PHONE"))
        assertFalse(manifest.contains("android.intent.action.CALL"))
    }

    private fun androidManifestText(): String {
        val candidates =
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("app/src/main/AndroidManifest.xml"),
            )
        return candidates.first { it.exists() }.readText()
    }
}
