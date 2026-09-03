/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CUSTOM_VOICE_PACKAGE_MANIFEST_FILE
import com.zeroclaw.android.model.CustomVoiceModelFileManifest
import com.zeroclaw.android.model.CustomVoicePackageManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeType
import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import com.zeroclaw.android.model.VoiceModelStatus
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("CustomVoiceSpeechOutputDriver")
class CustomVoiceSpeechOutputDriverTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `prepare resolves custom package and warms runtime without playback`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime = FakeCustomVoiceRuntime()
            val audioPlayer = FakeCustomVoiceAudioPlayer()
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        audioPlayer = audioPlayer,
                    ),
                )

            val result =
                adapter.prepare(
                    voice = importedVoice(packageRoot),
                )

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf(CustomVoiceRuntimeType.PIPER_V1), runtime.preparedRuntimeTypes)
            assertTrue(runtime.texts.isEmpty())
            assertTrue(audioPlayer.playedAudio.isEmpty())
        }

    @Test
    fun `stop only stops playback and keeps runtime model cached`() {
        val runtime = FakeCustomVoiceRuntime()
        val audioPlayer = FakeCustomVoiceAudioPlayer()
        val driver =
            CustomVoiceSpeechOutputDriver(
                runtime = runtime,
                audioPlayer = audioPlayer,
            )

        driver.stop()

        assertEquals(0, runtime.stopCalls)
        assertEquals(1, audioPlayer.stopCalls)
    }

    @Test
    fun `speak resolves custom package synthesizes locally and plays pcm`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime = FakeCustomVoiceRuntime()
            val audioPlayer = FakeCustomVoiceAudioPlayer()
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        audioPlayer = audioPlayer,
                    ),
                )

            val result =
                adapter.speak(
                    text = " Hello custom voice ",
                    voice = importedVoice(packageRoot),
                )

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf("Hello custom voice"), runtime.texts)
            assertEquals(listOf(CustomVoiceRuntimeType.PIPER_V1), runtime.runtimeTypes)
            assertEquals(22_050, audioPlayer.playedAudio.single().sampleRateHz)
        }

    @Test
    fun `speak plays custom voice stream chunks as they arrive`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime =
                FakeCustomVoiceRuntime(
                    streamChunks =
                        listOf(
                            byteArrayOf(0, 0, 1, 0),
                            byteArrayOf(2, 0, 3, 0),
                        ),
                )
            val audioPlayer = FakeCustomVoiceAudioPlayer()
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        audioPlayer = audioPlayer,
                    ),
                )

            val result =
                adapter.speak(
                    text = " Hello custom voice. Second sentence. ",
                    voice = importedVoice(packageRoot),
                )

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf("Hello custom voice. Second sentence."), runtime.streamTexts)
            assertEquals(2, audioPlayer.playedAudio.size)
            assertEquals(byteArrayOf(0, 0, 1, 0).toList(), audioPlayer.playedAudio[0].pcm16Mono.toList())
            assertEquals(byteArrayOf(2, 0, 3, 0).toList(), audioPlayer.playedAudio[1].pcm16Mono.toList())
        }

    @Test
    fun `speak reuses cached audio for repeated short phrases`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime = FakeCustomVoiceRuntime()
            val audioPlayer = FakeCustomVoiceAudioPlayer()
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        audioPlayer = audioPlayer,
                    ),
                )
            val voice = importedVoice(packageRoot)

            val first = adapter.speak(text = " Done. ", voice = voice)
            val second = adapter.speak(text = "Done.", voice = voice)

            assertEquals(SpeechSynthesisResult.Completed, first)
            assertEquals(SpeechSynthesisResult.Completed, second)
            assertEquals(listOf("Done."), runtime.texts)
            assertEquals(2, audioPlayer.playedAudio.size)
        }

    @Test
    fun `speak reuses resolved custom package without rechecking model checksum`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime = FakeCustomVoiceRuntime()
            val audioPlayer = FakeCustomVoiceAudioPlayer()
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        audioPlayer = audioPlayer,
                    ),
                )
            val voice = importedVoice(packageRoot)

            val first = adapter.speak(text = "First uncached phrase.", voice = voice)
            File(packageRoot, "models/clear.onnx").delete()
            val second = adapter.speak(text = "Second uncached phrase.", voice = voice)

            assertEquals(SpeechSynthesisResult.Completed, first)
            assertEquals(SpeechSynthesisResult.Completed, second)
            assertEquals(
                listOf("First uncached phrase.", "Second uncached phrase."),
                runtime.texts,
            )
        }

    @Test
    fun `speak fails quickly when first custom audio misses timeout`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime =
                FakeCustomVoiceRuntime(
                    streamChunks = listOf(byteArrayOf(0, 0, 1, 0)),
                    streamDelayMs = 50L,
                )
            val audioPlayer = FakeCustomVoiceAudioPlayer()
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        audioPlayer = audioPlayer,
                        firstAudioTimeoutMs = 1L,
                    ),
                )

            val result =
                adapter.speak(
                    text = " Hello custom voice ",
                    voice = importedVoice(packageRoot),
                )

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertEquals(
                CUSTOM_VOICE_FIRST_AUDIO_TIMEOUT_MESSAGE,
                (result as SpeechSynthesisResult.Failed).message,
            )
            assertTrue(audioPlayer.playedAudio.isEmpty())
        }

    @Test
    fun `first audio timeout opens cooldown circuit for same custom voice`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime =
                FakeCustomVoiceRuntime(
                    streamChunks = listOf(byteArrayOf(0, 0, 1, 0)),
                    streamDelayMs = 50L,
                )
            val audioPlayer = FakeCustomVoiceAudioPlayer()
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        audioPlayer = audioPlayer,
                        firstAudioTimeoutMs = 1L,
                    ),
                )
            val voice = importedVoice(packageRoot)

            val first = adapter.speak(text = "First failure narration.", voice = voice)
            val second = adapter.speak(text = "Second failure narration.", voice = voice)

            assertTrue(first is SpeechSynthesisResult.Failed)
            assertTrue(second is SpeechSynthesisResult.Failed)
            assertEquals(
                CUSTOM_VOICE_FIRST_AUDIO_TIMEOUT_MESSAGE,
                (second as SpeechSynthesisResult.Failed).message,
            )
            assertEquals(listOf("First failure narration."), runtime.streamTexts)
            assertTrue(audioPlayer.playedAudio.isEmpty())
        }

    @Test
    fun `custom timeout records one synthesis failure trace event`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime =
                FakeCustomVoiceRuntime(
                    streamChunks = listOf(byteArrayOf(0, 0, 1, 0)),
                    streamDelayMs = 50L,
                )
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        firstAudioTimeoutMs = 1L,
                    ),
                )
            val traceEvents = mutableListOf<String>()
            val trace =
                VoiceTurnTrace.forTest(
                    id = "voice-test",
                    source = "test",
                    startedAtMs = 0L,
                    nowMs = { 1L },
                    sink = { message -> traceEvents += message },
                )

            adapter.speak(
                text = "Failure narration.",
                voice = importedVoice(packageRoot),
                trace = trace,
            )

            assertEquals(
                1,
                traceEvents.count { event -> event.contains("event=tts_synthesis_failed") },
            )
        }

    @Test
    fun `speak accepts installed catalog voice packages for Piper playback`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime = FakeCustomVoiceRuntime()
            val audioPlayer = FakeCustomVoiceAudioPlayer()
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(
                        runtime = runtime,
                        audioPlayer = audioPlayer,
                    ),
                )

            val result =
                adapter.speak(
                    text = " Hello catalog voice ",
                    voice = importedVoice(packageRoot).copy(source = VoiceModelSource.CATALOG),
                )

            assertEquals(SpeechSynthesisResult.Completed, result)
            assertEquals(listOf("Hello catalog voice"), runtime.texts)
            assertEquals(22_050, audioPlayer.playedAudio.single().sampleRateHz)
        }

    @Test
    fun `speak rejects package when runtime does not support manifest type`() =
        runTest {
            val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
            val runtime = FakeCustomVoiceRuntime(supported = false)
            val adapter =
                LocalSpeechOutputAdapter(
                    CustomVoiceSpeechOutputDriver(runtime = runtime),
                )

            val result =
                adapter.speak(
                    text = "Hello",
                    voice = importedVoice(packageRoot),
                )

            assertTrue(result is SpeechSynthesisResult.Failed)
            assertTrue((result as SpeechSynthesisResult.Failed).message.contains("runtime"))
            assertTrue(runtime.texts.isEmpty())
        }

    private fun importedVoice(packageRoot: File): VoiceModel =
        VoiceModel(
            id = "imported-clear",
            displayName = "Imported Clear",
            toneLabel = "Custom",
            localeTag = "en-US",
            description = "Imported package.",
            sizeBytes = 3L,
            source = VoiceModelSource.IMPORTED,
            status = VoiceModelStatus.Installed,
            sampleText = "Ready.",
            modelUri = File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE).toURI().toString(),
        )

    private fun writeStoredPackage(modelBytes: ByteArray): File {
        val packageRoot = File(tempDir, "package-${System.nanoTime()}")
        val modelFile = File(packageRoot, "models/clear.onnx")
        modelFile.parentFile?.mkdirs()
        modelFile.writeBytes(modelBytes)
        File(packageRoot, "models/clear.json").writeText("{}")

        val manifest =
            CustomVoicePackageManifest(
                packageId = "en.clear.operator",
                displayName = "Clear Operator",
                localeTag = "en-US",
                sampleText = "I am ready locally.",
                runtime =
                    CustomVoiceRuntimeManifest(
                        type = CustomVoiceRuntimeType.PIPER_V1,
                        sampleRateHz = 22_050,
                    ),
                model =
                    CustomVoiceModelFileManifest(
                        path = "models/clear.onnx",
                        sizeBytes = modelBytes.size.toLong(),
                        sha256 = modelBytes.sha256(),
                        configPath = "models/clear.json",
                    ),
            )
        File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE)
            .writeText(Json.encodeToString(manifest))
        return packageRoot
    }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class FakeCustomVoiceRuntime(
        private val supported: Boolean = true,
        private val streamChunks: List<ByteArray> = emptyList(),
        private val streamDelayMs: Long = 0L,
    ) : CustomVoiceRuntime {
        override val status = MutableStateFlow<LocalSpeechEngineStatus>(LocalSpeechEngineStatus.Ready)
        val preparedRuntimeTypes = mutableListOf<String>()
        val texts = mutableListOf<String>()
        val runtimeTypes = mutableListOf<String>()
        val streamTexts = mutableListOf<String>()
        var stopCalls = 0
            private set

        override fun supports(runtimeType: String): Boolean = supported

        override suspend fun prepare(
            voicePackage: ResolvedCustomVoicePackage,
        ): SpeechSynthesisResult {
            preparedRuntimeTypes += voicePackage.manifest.runtime.type
            return SpeechSynthesisResult.Completed
        }

        override suspend fun synthesize(
            text: String,
            voicePackage: ResolvedCustomVoicePackage,
        ): CustomVoiceSynthesisResult {
            texts += text
            runtimeTypes += voicePackage.manifest.runtime.type
            return CustomVoiceSynthesisResult.Success(
                CustomVoicePcmAudio(
                    sampleRateHz = voicePackage.manifest.runtime.sampleRateHz,
                    pcm16Mono = byteArrayOf(0, 0, 1, 0),
                ),
            )
        }

        override fun synthesizeStream(
            text: String,
            voicePackage: ResolvedCustomVoicePackage,
            trace: VoiceTurnTrace,
        ): Flow<CustomVoiceSynthesisStreamEvent> {
            if (streamChunks.isEmpty()) {
                return super.synthesizeStream(text, voicePackage, trace)
            }
            streamTexts += text
            return flow {
                streamChunks.forEachIndexed { index, chunk ->
                    if (streamDelayMs > 0L) {
                        delay(streamDelayMs)
                    }
                    emit(
                        CustomVoiceSynthesisStreamEvent.Audio(
                            audio =
                                CustomVoicePcmAudio(
                                    sampleRateHz = voicePackage.manifest.runtime.sampleRateHz,
                                    pcm16Mono = chunk,
                                ),
                            segmentIndex = index,
                            segmentCount = streamChunks.size,
                        ),
                    )
                }
            }
        }

        override fun stop() {
            stopCalls += 1
        }
    }

    private class FakeCustomVoiceAudioPlayer : CustomVoiceAudioPlayer {
        val playedAudio = mutableListOf<CustomVoicePcmAudio>()
        var stopCalls = 0
            private set

        override suspend fun play(audio: CustomVoicePcmAudio): SpeechSynthesisResult {
            playedAudio += audio
            return SpeechSynthesisResult.Completed
        }

        override fun stop() {
            stopCalls += 1
        }
    }
}
