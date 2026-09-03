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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CompositeLocalSpeechOutputDriver")
class CompositeLocalSpeechOutputDriverTest {
    @Test
    fun `prepare delegates to resolved voice driver`() =
        runTest {
            val customDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.IMPORTED,
                    outputVoiceId = "custom-imported",
                    result = SpeechSynthesisResult.Completed,
                )
            val androidDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "android-en-us",
                    result = SpeechSynthesisResult.Completed,
                )
            val composite =
                CompositeLocalSpeechOutputDriver(
                    drivers = listOf(customDriver, androidDriver),
                    scope = backgroundScope,
                )

            val outputVoice = composite.findVoice(importedVoice())
            val result = composite.prepare(requireNotNull(outputVoice))

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf("custom-imported"), customDriver.preparedVoiceIds)
            assertEquals(emptyList<String>(), androidDriver.preparedVoiceIds)
        }

    @Test
    fun `imported voice returns custom runtime failure instead of using wrong fallback voice`() =
        runTest {
            val customDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.IMPORTED,
                    outputVoiceId = "custom-imported",
                    result =
                        SpeechSynthesisResult.Failed(
                            "Native eSpeak/piper-phonemize is not bundled in this build.",
                        ),
                )
            val androidDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "android-en-us",
                    result = SpeechSynthesisResult.Completed,
                )
            val composite =
                CompositeLocalSpeechOutputDriver(
                    drivers = listOf(customDriver, androidDriver),
                    scope = backgroundScope,
                )

            val outputVoice = composite.findVoice(importedVoice())
            val result = composite.speak("Hello there.", requireNotNull(outputVoice))

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertEquals(
                "Native eSpeak/piper-phonemize is not bundled in this build.",
                (result as SpeechSynthesisResult.Failed).message,
            )
            assertEquals(listOf("Hello there."), customDriver.spokenTexts)
            assertEquals(emptyList<String>(), androidDriver.spokenTexts)
        }

    @Test
    fun `catalog voice does not fall back after primary failure`() =
        runTest {
            val failingDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "catalog-primary",
                    result = SpeechSynthesisResult.Failed("Offline voice failed."),
                )
            val fallbackDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "catalog-fallback",
                    result = SpeechSynthesisResult.Completed,
                )
            val composite =
                CompositeLocalSpeechOutputDriver(
                    drivers = listOf(failingDriver, fallbackDriver),
                    scope = backgroundScope,
                )

            val outputVoice = composite.findVoice(catalogVoice())
            val result = composite.speak("Hello there.", requireNotNull(outputVoice))

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertEquals(emptyList<String>(), fallbackDriver.spokenTexts)
        }

    @Test
    fun `auto routing prefers Android TTS for catalog voices on low tier devices`() =
        runTest {
            val customDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "custom-catalog",
                    result = SpeechSynthesisResult.Completed,
                    engine = LocalSpeechOutputEngine.CUSTOM_VOICE,
                )
            val androidDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "android-en-us",
                    result = SpeechSynthesisResult.Completed,
                    engine = LocalSpeechOutputEngine.ANDROID_TTS,
                )
            val composite =
                CompositeLocalSpeechOutputDriver(
                    drivers = listOf(customDriver, androidDriver),
                    scope = backgroundScope,
                    routingPolicy =
                        VoiceOutputRoutingPolicy(
                            deviceProfile = lowTierProfile(),
                        ),
                )

            val outputVoice = composite.findVoice(catalogPackageVoice())
            val result = composite.speak("Hello there.", requireNotNull(outputVoice))

            assertEquals("android-en-us", outputVoice.id)
            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(emptyList<String>(), customDriver.spokenTexts)
            assertEquals(listOf("Hello there."), androidDriver.spokenTexts)
        }

    @Test
    fun `quality routing prefers custom Piper for catalog package voices`() =
        runTest {
            val customDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "custom-catalog",
                    result = SpeechSynthesisResult.Completed,
                    engine = LocalSpeechOutputEngine.CUSTOM_VOICE,
                )
            val androidDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "android-en-us",
                    result = SpeechSynthesisResult.Completed,
                    engine = LocalSpeechOutputEngine.ANDROID_TTS,
                )
            val composite =
                CompositeLocalSpeechOutputDriver(
                    drivers = listOf(androidDriver, customDriver),
                    scope = backgroundScope,
                    routingPolicy =
                        VoiceOutputRoutingPolicy(
                            mode = VoicePerformanceMode.QUALITY,
                            deviceProfile = lowTierProfile(),
                        ),
                )

            val outputVoice = composite.findVoice(catalogPackageVoice())
            val result = composite.speak("Hello there.", requireNotNull(outputVoice))

            assertEquals("custom-catalog", outputVoice.id)
            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf("Hello there."), customDriver.spokenTexts)
            assertEquals(emptyList<String>(), androidDriver.spokenTexts)
        }

    @Test
    fun `custom timeout falls back to Android TTS for catalog voices`() =
        runTest {
            val customDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "custom-catalog",
                    result = SpeechSynthesisResult.Failed(CUSTOM_VOICE_FIRST_AUDIO_TIMEOUT_MESSAGE),
                    engine = LocalSpeechOutputEngine.CUSTOM_VOICE,
                )
            val androidDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "android-en-us",
                    result = SpeechSynthesisResult.Completed,
                    engine = LocalSpeechOutputEngine.ANDROID_TTS,
                )
            val composite =
                CompositeLocalSpeechOutputDriver(
                    drivers = listOf(customDriver, androidDriver),
                    scope = backgroundScope,
                    routingPolicy =
                        VoiceOutputRoutingPolicy(
                            mode = VoicePerformanceMode.QUALITY,
                            deviceProfile = lowTierProfile(),
                        ),
                )

            val outputVoice = composite.findVoice(catalogPackageVoice())
            val result = composite.speak("Hello there.", requireNotNull(outputVoice))

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf("Hello there."), customDriver.spokenTexts)
            assertEquals(listOf("Hello there."), androidDriver.spokenTexts)
        }

    @Test
    fun `routing policy reacts to performance mode changes`() =
        runTest {
            val customDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "custom-catalog",
                    result = SpeechSynthesisResult.Completed,
                    engine = LocalSpeechOutputEngine.CUSTOM_VOICE,
                )
            val androidDriver =
                FakeSpeechOutputDriver(
                    acceptedSource = VoiceModelSource.CATALOG,
                    outputVoiceId = "android-en-us",
                    result = SpeechSynthesisResult.Completed,
                    engine = LocalSpeechOutputEngine.ANDROID_TTS,
                )
            val preferences = InMemoryVoiceOutputPreferences(VoicePerformanceMode.FAST)
            val composite =
                CompositeLocalSpeechOutputDriver(
                    drivers = listOf(customDriver, androidDriver),
                    scope = backgroundScope,
                    routingPolicy =
                        VoiceOutputRoutingPolicy(
                            deviceProfile = lowTierProfile(),
                            modeProvider = { preferences.performanceMode.value },
                        ),
                )

            assertEquals("android-en-us", composite.findVoice(catalogPackageVoice())?.id)

            preferences.setPerformanceMode(VoicePerformanceMode.QUALITY)

            assertEquals("custom-catalog", composite.findVoice(catalogPackageVoice())?.id)
        }

    private fun importedVoice(): VoiceModel =
        VoiceModel(
            id = "imported-ryan",
            displayName = "Ryan",
            toneLabel = "Custom",
            localeTag = "en-US",
            description = "Imported voice.",
            sizeBytes = 1L,
            source = VoiceModelSource.IMPORTED,
            status = VoiceModelStatus.Installed,
            sampleText = "Ready.",
            modelUri = "file:///voices/ryan/voice-package.json",
        )

    private fun catalogVoice(): VoiceModel =
        importedVoice().copy(
            id = "en-calm-guide",
            source = VoiceModelSource.CATALOG,
            modelUri = "android-tts://en-calm-guide",
        )

    private fun catalogPackageVoice(): VoiceModel =
        catalogVoice().copy(
            modelUri = "file:///voices/calm/voice-package.json",
        )

    private fun lowTierProfile(): VoiceDeviceProfile =
        VoiceDeviceProfile(
            tier = VoiceDeviceTier.LOW,
            totalRamMb = 3_072L,
            availableProcessors = 4,
        )

    private class FakeSpeechOutputDriver(
        private val acceptedSource: VoiceModelSource,
        private val outputVoiceId: String,
        private val result: SpeechSynthesisResult,
        override val engine: LocalSpeechOutputEngine = LocalSpeechOutputEngine.OTHER,
    ) : LocalSpeechOutputDriver {
        override val status = MutableStateFlow<LocalSpeechEngineStatus>(LocalSpeechEngineStatus.Ready)
        val preparedVoiceIds = mutableListOf<String>()
        val spokenTexts = mutableListOf<String>()

        override fun findVoice(voice: VoiceModel): LocalSpeechOutputVoice? =
            if (voice.source == acceptedSource) {
                LocalSpeechOutputVoice(
                    id = outputVoiceId,
                    localeTag = voice.localeTag,
                )
            } else {
                null
            }

        override suspend fun prepare(voice: LocalSpeechOutputVoice): SpeechSynthesisResult {
            preparedVoiceIds += voice.id
            return result
        }

        override suspend fun speak(
            text: String,
            voice: LocalSpeechOutputVoice,
        ): SpeechSynthesisResult {
            spokenTexts += text
            return result
        }

        override fun stop() = Unit
    }
}
