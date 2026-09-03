/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import com.zeroclaw.android.model.VoiceModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LocalSpeechOutputAdapter")
class LocalSpeechOutputAdapterTest {
    @Test
    fun `prepare resolves selected voice and warms local driver`() =
        runTest {
            val driver = FakeLocalSpeechOutputDriver()
            val adapter = LocalSpeechOutputAdapter(driver)

            val result = adapter.prepare(installedVoice())

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf("driver-en-us"), driver.preparedVoiceIds)
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `speak delegates trimmed text to offline local driver`() =
        runTest {
            val driver = FakeLocalSpeechOutputDriver()
            val adapter = LocalSpeechOutputAdapter(driver)
            val voice = installedVoice()

            val result = adapter.speak("  Hello locally.  ", voice)

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf("Hello locally."), driver.spokenTexts)
            assertEquals(listOf("driver-en-us"), driver.spokenVoiceIds)
        }

    @Test
    fun `speak normalizes terse assistant text before local playback`() =
        runTest {
            val driver = FakeLocalSpeechOutputDriver()
            val adapter = LocalSpeechOutputAdapter(driver)
            val voice = installedVoice()

            val result =
                adapter.speak(
                    "- AI status: 50% ready & OK / waiting",
                    voice,
                )

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(
                listOf("A I status: 50 percent ready and okay or waiting"),
                driver.spokenTexts,
            )
        }

    @Test
    fun `speak rejects blank text without calling driver`() =
        runTest {
            val driver = FakeLocalSpeechOutputDriver()
            val adapter = LocalSpeechOutputAdapter(driver)

            val result = adapter.speak("   ", installedVoice())

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertTrue((result as SpeechSynthesisResult.Failed).message.contains("blank"))
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `speak rejects non English voices first`() =
        runTest {
            val driver = FakeLocalSpeechOutputDriver()
            val adapter = LocalSpeechOutputAdapter(driver)

            val result = adapter.speak("Hello", installedVoice(localeTag = "hi-IN"))

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertTrue((result as SpeechSynthesisResult.Failed).message.contains("English"))
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `speak rejects voices that are not installed`() =
        runTest {
            val driver = FakeLocalSpeechOutputDriver()
            val adapter = LocalSpeechOutputAdapter(driver)

            val result =
                adapter.speak(
                    "Hello",
                    installedVoice(status = VoiceModelStatus.AvailableForDownload),
                )

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertTrue((result as SpeechSynthesisResult.Failed).message.contains("Install"))
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `speak requires installed voices to have local model uri`() =
        runTest {
            val driver = FakeLocalSpeechOutputDriver()
            val adapter = LocalSpeechOutputAdapter(driver)

            val result = adapter.speak("Hello", installedVoice(modelUri = null))

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertTrue((result as SpeechSynthesisResult.Failed).message.contains("local model URI"))
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `speak rejects unavailable engine status before driver playback`() =
        runTest {
            val driver =
                FakeLocalSpeechOutputDriver(
                    initialStatus = LocalSpeechEngineStatus.Unavailable("No local TTS engine."),
                )
            val adapter = LocalSpeechOutputAdapter(driver)

            val result = adapter.speak("Hello", installedVoice())

            assertEquals(SpeechSynthesisResult.Failed("No local TTS engine."), result)
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `speak waits while local engine is initializing`() =
        runTest {
            val driver =
                FakeLocalSpeechOutputDriver(
                    initialStatus = LocalSpeechEngineStatus.Initializing,
                )
            val adapter = LocalSpeechOutputAdapter(driver)

            val result = adapter.speak("Hello", installedVoice())

            assertEquals(
                SpeechSynthesisResult.Failed("Local speech output is still initializing."),
                result,
            )
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `speak rejects missing offline driver voice`() =
        runTest {
            val driver = FakeLocalSpeechOutputDriver(outputVoice = null)
            val adapter = LocalSpeechOutputAdapter(driver)

            val result = adapter.speak("Hello", installedVoice())

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertTrue((result as SpeechSynthesisResult.Failed).message.contains("local model runtime"))
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `speak blocks network backed driver voices`() =
        runTest {
            val driver =
                FakeLocalSpeechOutputDriver(
                    outputVoice =
                        LocalSpeechOutputVoice(
                            id = "network-en-us",
                            localeTag = "en-US",
                            requiresNetwork = true,
                        ),
                )
            val adapter = LocalSpeechOutputAdapter(driver)

            val result = adapter.speak("Hello", installedVoice())

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertTrue((result as SpeechSynthesisResult.Failed).message.contains("network"))
            assertFalse(driver.wasSpeakCalled)
        }

    @Test
    fun `status reflects driver state and stop delegates`() {
        val driver = FakeLocalSpeechOutputDriver()
        val adapter = LocalSpeechOutputAdapter(driver)

        driver.status.value = LocalSpeechEngineStatus.MissingModel
        adapter.stop()

        assertEquals(LocalSpeechEngineStatus.MissingModel, adapter.status.value)
        assertTrue(driver.wasStopCalled)
    }

    private fun installedVoice(
        localeTag: String = "en-US",
        status: VoiceModelStatus = VoiceModelStatus.Installed,
        modelUri: String? = "file:///voices/local-en-us.onnx",
    ): VoiceModel =
        VoiceModel(
            id = "en-local",
            displayName = "Local English",
            toneLabel = "Clear",
            localeTag = localeTag,
            description = "Local test voice.",
            sizeBytes = 42L,
            source = VoiceModelSource.IMPORTED,
            status = status,
            sampleText = "Ready.",
            modelUri = modelUri,
        )

    private class FakeLocalSpeechOutputDriver(
        initialStatus: LocalSpeechEngineStatus = LocalSpeechEngineStatus.Ready,
        private val outputVoice: LocalSpeechOutputVoice? =
            LocalSpeechOutputVoice(
                id = "driver-en-us",
                localeTag = "en-US",
            ),
    ) : LocalSpeechOutputDriver {
        override val status = MutableStateFlow(initialStatus)
        val preparedVoiceIds = mutableListOf<String>()
        val spokenTexts = mutableListOf<String>()
        val spokenVoiceIds = mutableListOf<String>()
        var wasStopCalled = false

        val wasSpeakCalled: Boolean
            get() = spokenTexts.isNotEmpty()

        override fun findVoice(voice: VoiceModel): LocalSpeechOutputVoice? = outputVoice

        override suspend fun prepare(voice: LocalSpeechOutputVoice): SpeechSynthesisResult {
            preparedVoiceIds += voice.id
            return SpeechSynthesisResult.Completed
        }

        override suspend fun speak(
            text: String,
            voice: LocalSpeechOutputVoice,
        ): SpeechSynthesisResult {
            spokenTexts += text
            spokenVoiceIds += voice.id
            return SpeechSynthesisResult.Completed
        }

        override fun stop() {
            wasStopCalled = true
        }
    }
}
