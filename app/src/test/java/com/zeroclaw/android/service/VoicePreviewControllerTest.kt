/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoicePreviewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoicePreviewController")
class VoicePreviewControllerTest {
    @Test
    fun `preview requires installed voice`() =
        runTest {
            val repository = LocalVoiceCatalogRepository()
            val controller =
                VoicePreviewController(
                    voiceCatalogRepository = repository,
                    synthesizer = FakeSpeechSynthesizer(),
                )

            val result = controller.preview("en-calm-guide")

            assertTrue(result is VoicePreviewState.Unavailable)
        }

    @Test
    fun `preview reports missing local runtime`() =
        runTest {
            val repository = LocalVoiceCatalogRepository()
            repository.markInstalled("en-calm-guide")
            val controller =
                VoicePreviewController(
                    voiceCatalogRepository = repository,
                    synthesizer = MissingLocalSpeechSynthesizer("No local engine."),
                )

            val result = controller.preview("en-calm-guide")

            assertEquals(VoicePreviewState.Unavailable("No local engine."), result)
        }

    @Test
    fun `preview speaks sample text through ready local synthesizer`() =
        runTest {
            val repository = LocalVoiceCatalogRepository()
            repository.markInstalled("en-calm-guide")
            val synthesizer = FakeSpeechSynthesizer()
            val controller =
                VoicePreviewController(
                    voiceCatalogRepository = repository,
                    synthesizer = synthesizer,
                )

            val result = controller.preview("en-calm-guide")

            assertEquals(VoicePreviewState.Completed("en-calm-guide"), result)
            assertEquals("I am ready when you are.", synthesizer.lastText)
            assertEquals("en-calm-guide", synthesizer.lastVoiceId)
        }

    @Test
    fun `preview surfaces synthesizer failure`() =
        runTest {
            val repository = LocalVoiceCatalogRepository()
            repository.markInstalled("en-calm-guide")
            val controller =
                VoicePreviewController(
                    voiceCatalogRepository = repository,
                    synthesizer =
                        FakeSpeechSynthesizer(
                            result = SpeechSynthesisResult.Failed("Playback failed."),
                        ),
                )

            val result = controller.preview("en-calm-guide")

            assertEquals(VoicePreviewState.Failed("Playback failed."), result)
        }

    private class FakeSpeechSynthesizer(
        initialStatus: LocalSpeechEngineStatus = LocalSpeechEngineStatus.Ready,
        private val result: SpeechSynthesisResult = SpeechSynthesisResult.Completed,
    ) : LocalSpeechSynthesizer {
        override val status = MutableStateFlow(initialStatus)
        var lastText: String? = null
            private set
        var lastVoiceId: String? = null
            private set

        override suspend fun speak(
            text: String,
            voice: VoiceModel,
        ): SpeechSynthesisResult {
            lastText = text
            lastVoiceId = voice.id
            return result
        }

        override fun stop() = Unit
    }
}
