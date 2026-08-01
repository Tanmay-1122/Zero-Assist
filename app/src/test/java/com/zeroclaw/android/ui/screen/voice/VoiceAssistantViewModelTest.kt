/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.voice

import com.zeroclaw.android.model.VoiceAssistantCommand
import com.zeroclaw.android.model.VoiceAssistantSessionState
import com.zeroclaw.android.model.VoiceAssistantDeviceAction
import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import com.zeroclaw.android.model.VoiceModelStatus
import com.zeroclaw.android.service.DeviceAction
import com.zeroclaw.android.service.InMemoryVoiceOutputPreferences
import com.zeroclaw.android.service.InMemoryVoiceWakeupPreferences
import com.zeroclaw.android.service.LocalSpeechEngineStatus
import com.zeroclaw.android.service.LocalSpeechRecognizer
import com.zeroclaw.android.service.LocalSpeechSynthesizer
import com.zeroclaw.android.service.LocalVoiceCatalogRepository
import com.zeroclaw.android.service.LocalVoiceStorage
import com.zeroclaw.android.service.SpeechRecognitionEvent
import com.zeroclaw.android.service.SpeechSynthesisResult
import com.zeroclaw.android.service.VoiceAssistantConversation
import com.zeroclaw.android.service.VoiceAssistantConversationResult
import com.zeroclaw.android.service.VoiceAssistantCommandLaunchResult
import com.zeroclaw.android.service.VoiceContactLookup
import com.zeroclaw.android.service.VoiceContactLookupResult
import com.zeroclaw.android.service.VoiceWakeupDetector
import com.zeroclaw.android.service.VoiceWakeupDetectorStatus
import com.zeroclaw.android.service.VoiceWakeupServiceCommandResult
import com.zeroclaw.android.service.VoiceWakeupServiceController
import com.zeroclaw.android.service.VoiceWakeupStartResult
import com.zeroclaw.android.service.VoicePerformanceMode
import com.zeroclaw.android.service.uiagent.UiAgentGoal
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("VoiceAssistantViewModel")
class VoiceAssistantViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startListening captures local final transcript`() =
        runTest {
            val repository = installedVoiceRepository()
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events =
                        listOf(
                            SpeechRecognitionEvent.PartialTranscript("summarize"),
                            SpeechRecognitionEvent.FinalTranscript("summarize notes"),
                        ),
                )
            val conversation =
                RecordingVoiceAssistantConversation(
                    VoiceAssistantConversationResult.Success("Done. I summarized your notes."),
                )
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation = conversation,
                    localSpeechSynthesizer = synthesizer,
                )
            val stateDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.uiState.first { it.statusMessage == "Done. I summarized your notes." }
                }

            viewModel.openPopup()
            viewModel.startListening()

            val state = stateDeferred.await()
            assertEquals("summarize notes", state.lastTranscript)
            assertEquals("", state.partialTranscript)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
            assertEquals(listOf("summarize notes"), conversation.transcripts)
            assertEquals(listOf("Done. I summarized your notes."), synthesizer.spokenTexts)
            assertEquals(1, recognizer.listenCalls)
            assertTrue(recognizer.stopCalls >= 1)
        }

    @Test
    fun `submitTextPrompt sends text through assistant conversation`() =
        runTest {
            val conversation =
                RecordingVoiceAssistantConversation(
                    VoiceAssistantConversationResult.Success("Done. I summarized your notes."),
                )
            val recognizer = FakeLocalSpeechRecognizer(events = emptyList())
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation = conversation,
                    localSpeechSynthesizer = synthesizer,
                )
            val stateDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.uiState.first { it.statusMessage == "Done. I summarized your notes." }
                }

            viewModel.openPopup()
            viewModel.submitTextPrompt("  summarize notes  ")

            val state = stateDeferred.await()
            assertEquals("summarize notes", state.lastTranscript)
            assertEquals("", state.partialTranscript)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
            assertEquals(listOf("summarize notes"), conversation.transcripts)
            assertEquals(0, recognizer.listenCalls)
            assertEquals(listOf("Done. I summarized your notes."), synthesizer.spokenTexts)
        }

    @Test
    fun `submitTextPrompt works when local voice is not installed`() =
        runTest {
            val conversation =
                RecordingVoiceAssistantConversation(
                    VoiceAssistantConversationResult.Success("Typed response."),
                )
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = LocalVoiceCatalogRepository(),
                    voiceAssistantConversation = conversation,
                )
            val stateDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.uiState.first { it.statusMessage == "Typed response." }
                }

            viewModel.submitTextPrompt("summarize the dashboard notes")

            val state = stateDeferred.await()
            assertEquals("summarize the dashboard notes", state.lastTranscript)
            assertEquals(VoiceAssistantSessionState.MissingVoice, state.sessionState)
            assertEquals(listOf("summarize the dashboard notes"), conversation.transcripts)
        }

    @Test
    fun `startListening ignores duplicate request while recognizer is active`() =
        runTest {
            val recognizer = BlockingLocalSpeechRecognizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechRecognizer = recognizer,
                )
            try {
                viewModel.openPopup()
                viewModel.startListening()
                recognizer.started.await()

                viewModel.startListening()

                assertEquals(1, recognizer.listenCalls)
                assertEquals(1, recognizer.stopCalls)
            } finally {
                clearViewModel(viewModel)
            }
        }

    @Test
    fun `startListening pauses wakeup service while foreground voice turn owns microphone`() =
        runTest {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events = listOf(SpeechRecognitionEvent.FinalTranscript("summarize notes")),
                )
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation =
                        RecordingVoiceAssistantConversation(
                            VoiceAssistantConversationResult.Success("Done."),
                        ),
                    voiceWakeupDetector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    voiceWakeupServiceController = controller,
                    localSpeechSynthesizer = synthesizer,
                )

            viewModel.setWakeupEnabled(true)
            viewModel.openPopup()
            viewModel.startListening()

            viewModel.uiState.first { it.statusMessage == "Done." }
            testScheduler.advanceUntilIdle()
            assertEquals(2, controller.startCalls)
            assertEquals(1, controller.stopCalls)
        }

    @Test
    fun `openPopup prewarms selected voice without speaking`() =
        runTest {
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechSynthesizer = synthesizer,
                )

            viewModel.openPopup()

            assertEquals(listOf("en-calm-guide"), synthesizer.preparedVoiceIds)
            assertEquals(emptyList<String>(), synthesizer.spokenTexts)
        }

    @Test
    fun `openPopup skips expensive imported voice prewarm`() =
        runTest {
            val repository =
                LocalVoiceCatalogRepository().apply {
                    importInstalledVoice(
                        VoiceModel(
                            id = "imported-jarvis",
                            displayName = "Jarvis",
                            toneLabel = "Imported",
                            localeTag = "en-US",
                            description = "Local Piper voice.",
                            sizeBytes = 42L,
                            source = VoiceModelSource.IMPORTED,
                            status = VoiceModelStatus.Installed,
                            sampleText = "Ready.",
                            modelUri = "file:///voices/jarvis/manifest.json",
                        ),
                    )
                }
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localSpeechSynthesizer = synthesizer,
                )

            viewModel.openPopup()

            assertEquals(emptyList<String>(), synthesizer.preparedVoiceIds)
            assertEquals(emptyList<String>(), synthesizer.spokenTexts)
        }

    @Test
    fun `startListening speaks streamed sentences without repeating final response`() =
        runTest {
            val repository = installedVoiceRepository()
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events = listOf(SpeechRecognitionEvent.FinalTranscript("what changed")),
                )
            val conversation = EarlySentenceVoiceAssistantConversation()
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation = conversation,
                    localSpeechSynthesizer = synthesizer,
                )
            val stateDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.uiState.first {
                        it.statusMessage == "First answer. Second answer."
                    }
                }

            viewModel.openPopup()
            viewModel.startListening()

            assertEquals(listOf("First answer.", "Second answer."), synthesizer.spokenTexts)

            conversation.finish.complete(Unit)
            val state = stateDeferred.await()
            testScheduler.advanceUntilIdle()

            assertEquals("First answer. Second answer.", state.statusMessage)
            assertEquals(listOf("First answer.", "Second answer."), synthesizer.spokenTexts)
        }

    @Test
    fun `closePopup cancels an in-flight voice conversation`() =
        runTest {
            val repository = installedVoiceRepository()
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events = listOf(SpeechRecognitionEvent.FinalTranscript("summarize notes")),
                )
            val conversation = BlockingVoiceAssistantConversation()
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation = conversation,
                    localSpeechSynthesizer = synthesizer,
                )
            try {
                viewModel.openPopup()
                viewModel.startListening()
                conversation.started.await()
                viewModel.uiState.first {
                    it.sessionState == VoiceAssistantSessionState.Processing ||
                        it.sessionState == VoiceAssistantSessionState.Speaking
                }
                val cancelCallsBeforeClose = conversation.cancelCalls

                viewModel.closePopup()
                testScheduler.advanceUntilIdle()

                val state = viewModel.uiState.value
                assertTrue(conversation.cancelCalls > cancelCallsBeforeClose)
                assertTrue(synthesizer.stopCalls >= 1)
                assertFalse(state.popupVisible)
                assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
            } finally {
                clearViewModel(viewModel)
            }
        }

    @Test
    fun `startListening surfaces voice conversation failures`() =
        runTest {
            val repository = installedVoiceRepository()
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events = listOf(SpeechRecognitionEvent.FinalTranscript("summarize notes")),
                )
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation =
                        RecordingVoiceAssistantConversation(
                            VoiceAssistantConversationResult.Failed("Voice assistant session is unavailable."),
                        ),
                )

            viewModel.openPopup()
            viewModel.startListening()

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Voice assistant session is unavailable."
                }
            assertEquals("summarize notes", state.lastTranscript)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
        }

    @Test
    fun `startListening routes phone-like commands without full voice conversation`() =
        runTest {
            val transcript = "call +1 555 123 4567"
            val repository = installedVoiceRepository()
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events = listOf(SpeechRecognitionEvent.FinalTranscript(transcript)),
                )
            val conversation = RecordingVoiceAssistantConversation()
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation = conversation,
                    localSpeechSynthesizer = synthesizer,
                )
            val commandDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.voiceCommands.first()
                }

            viewModel.openPopup()
            viewModel.startListening()

            val state =
                viewModel.uiState.first {
                    it.lastTranscript == transcript && it.statusMessage == "Opening phone dialer."
                }
            val command = commandDeferred.await()
            assertEquals(transcript, state.lastTranscript)
            assertEquals(transcript, command.text)
            assertEquals("tel:+15551234567", command.phoneDialUri)
            assertEquals(emptyList<String>(), conversation.transcripts)
            assertEquals(emptyList<String>(), synthesizer.spokenTexts)
        }

    @Test
    fun `startListening stays blocked while a phone task is running`() =
        runTest {
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events = listOf(SpeechRecognitionEvent.FinalTranscript("open youtube")),
                )
            val conversation = RecordingVoiceAssistantConversation()
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation = conversation,
                    localSpeechSynthesizer = synthesizer,
                )

            viewModel.openPopup()
            viewModel.startListening()

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Opening YouTube."
                }
            assertTrue(state.popupVisible)
            assertEquals(VoiceAssistantSessionState.Processing, state.sessionState)
            assertEquals(0, recognizer.listenCalls)
            assertEquals(emptyList<String>(), conversation.transcripts)
            assertEquals(emptyList<String>(), synthesizer.preparedVoiceIds)
            assertEquals(emptyList<String>(), synthesizer.spokenTexts)
        }

    @Test
    fun `active phone task cancels in-flight voice work`() =
        runTest {
            val recognizer = BlockingLocalSpeechRecognizer()
            val synthesizer = BlockingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechRecognizer = recognizer,
                    localSpeechSynthesizer = synthesizer,
                )

            viewModel.openPopup()
            synthesizer.prepareStarted.await()
            viewModel.startListening()
            recognizer.started.await()

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Opening YouTube."
                }
            assertTrue(state.popupVisible)
            assertEquals(VoiceAssistantSessionState.Processing, state.sessionState)
            assertTrue(synthesizer.prepareCancelled.isCompleted)
            assertTrue(recognizer.stopCalls >= 1)
            assertTrue(synthesizer.stopCalls >= 1)
        }

    @Test
    fun `startListening handles voice help without full voice conversation`() =
        runTest {
            val transcript = "what can you do"
            val repository = installedVoiceRepository()
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events = listOf(SpeechRecognitionEvent.FinalTranscript(transcript)),
                )
            val conversation = RecordingVoiceAssistantConversation()
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localSpeechRecognizer = recognizer,
                    voiceAssistantConversation = conversation,
                    localSpeechSynthesizer = synthesizer,
                )
            val response =
                "I can open apps, call contacts, set timers, control the phone, and answer short questions."

            viewModel.openPopup()
            viewModel.startListening()

            val state =
                viewModel.uiState.first {
                    it.lastTranscript == transcript && it.statusMessage == response
                }
            assertEquals(response, state.statusMessage)
            assertEquals(emptyList<String>(), conversation.transcripts)
            assertEquals(listOf(response), synthesizer.spokenTexts)
        }

    @Test
    fun `startListening surfaces recognizer errors`() =
        runTest {
            val repository = installedVoiceRepository()
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events =
                        listOf(
                            SpeechRecognitionEvent.Error(
                                "Install an offline English speech recognition service on this phone.",
                            ),
                        ),
                )
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localSpeechRecognizer = recognizer,
                )

            viewModel.openPopup()
            viewModel.startListening()

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Install an offline English speech recognition service on this phone."
                }
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
            assertEquals("", state.partialTranscript)
            assertEquals(1, recognizer.listenCalls)
        }

    @Test
    fun `popup disables listening when offline speech recognition is missing`() =
        runTest {
            val recognizer =
                FakeLocalSpeechRecognizer(
                    events = emptyList(),
                    initialStatus = LocalSpeechEngineStatus.MissingModel,
                )
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechRecognizer = recognizer,
                )

            viewModel.openPopup()
            viewModel.startListening()

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Install an offline English speech recognition service on this phone."
                }
            assertEquals(false, state.canListen)
            assertEquals(false, state.speechRecognitionAvailable)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
            assertEquals(0, recognizer.listenCalls)
        }

    @Test
    fun `microphonePermissionDenied updates popup message`() =
        runTest {
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechRecognizer = FakeLocalSpeechRecognizer(emptyList()),
                )

            viewModel.microphonePermissionDenied()

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Microphone permission is required for local voice mode."
                }
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
        }

    @Test
    fun `wakeup preference loads persisted request as unavailable`() =
        runTest {
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    voiceWakeupPreferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = true),
                )

            val state = viewModel.uiState.first { it.wakeupEnabled }

            assertEquals(false, state.wakeupAvailable)
            assertEquals(
                "Wake-up preference saved. Background wake word is unavailable until a local detector is bundled.",
                state.wakeupStatusMessage,
            )
        }

    @Test
    fun `wakeup readiness reflects available local detector without starting it`() =
        runTest {
            val detector =
                FakeVoiceWakeupDetector(
                    readyWakeupStatus(),
                )
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    voiceWakeupPreferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = true),
                    voiceWakeupDetector = detector,
                )

            val state = viewModel.uiState.first { it.wakeupAvailable }

            assertEquals(true, state.wakeupEnabled)
            assertEquals(
                "Local wake-word detector is available for foreground service startup.",
                state.wakeupStatusMessage,
            )
            assertEquals(0, detector.startCalls)
        }

    @Test
    fun `persisted wakeup request does not start foreground service from ViewModel construction`() =
        runTest {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)

            VoiceAssistantViewModel(
                voiceCatalogRepository = installedVoiceRepository(),
                voiceWakeupPreferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = true),
                voiceWakeupDetector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                voiceWakeupServiceController = controller,
            )

            assertEquals(0, controller.startCalls)
            assertEquals(0, controller.stopCalls)
        }

    @Test
    fun `setWakeupEnabled starts foreground service when detector and microphone are ready`() =
        runTest {
            val wakeupPreferences = InMemoryVoiceWakeupPreferences()
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    voiceWakeupPreferences = wakeupPreferences,
                    voiceWakeupDetector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    voiceWakeupServiceController = controller,
                )

            viewModel.setWakeupEnabled(true)

            assertEquals(true, wakeupPreferences.wakeupRequested.first { it })
            assertEquals(1, controller.startCalls)
            assertEquals(0, controller.stopCalls)
            val state =
                viewModel.uiState.first {
                    it.statusMessage == STARTED_WAKEUP_MESSAGE
                }
            assertEquals(true, state.wakeupEnabled)
            assertEquals(true, state.wakeupAvailable)
        }

    @Test
    fun `setWakeupEnabled does not start foreground service without microphone permission`() =
        runTest {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = false)
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    voiceWakeupDetector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    voiceWakeupServiceController = controller,
                )

            viewModel.setWakeupEnabled(true)

            assertEquals(0, controller.startCalls)
            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Wake-up preference saved. Microphone permission is required for wake-up mode."
                }
            assertEquals(true, state.wakeupEnabled)
            assertEquals(true, state.wakeupAvailable)
        }

    @Test
    fun `setWakeupEnabled stops foreground service when disabled`() =
        runTest {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    voiceWakeupDetector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    voiceWakeupServiceController = controller,
                )

            viewModel.setWakeupEnabled(true)
            viewModel.setWakeupEnabled(false)

            assertEquals(1, controller.startCalls)
            assertEquals(1, controller.stopCalls)
            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Wake-up request disabled."
                }
            assertEquals(false, state.wakeupEnabled)
        }

    @Test
    fun `setWakeupEnabled persists request but reports detector unavailable`() =
        runTest {
            val wakeupPreferences = InMemoryVoiceWakeupPreferences()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    voiceWakeupPreferences = wakeupPreferences,
                )

            viewModel.setWakeupEnabled(true)

            val requested = wakeupPreferences.wakeupRequested.first { it }
            val state =
                viewModel.uiState.first {
                    it.statusMessage ==
                        "Wake-up preference saved. Background wake word is unavailable until a local detector is bundled."
                }
            assertEquals(true, requested)
            assertEquals(true, state.wakeupEnabled)
            assertEquals(false, state.wakeupAvailable)
        }

    @Test
    fun `commandLaunchFailed keeps popup open and reports unavailable local timer app`() =
        runTest {
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                )

            viewModel.openPopup()
            viewModel.commandLaunchFailed(
                VoiceAssistantCommand(
                    text = "set a timer for 5 minutes",
                    deviceAction =
                        VoiceAssistantDeviceAction.SetTimer(
                            lengthSeconds = 300,
                            message = "Zero-Assist timer",
                        ),
                ),
            )

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "No local timer app is available on this device."
                }
            assertTrue(state.popupVisible)
            assertEquals("set a timer for 5 minutes", state.lastTranscript)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
        }

    @Test
    fun `commandLaunchFailed reports unavailable local calendar app for reminders`() =
        runTest {
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                )

            viewModel.openPopup()
            viewModel.commandLaunchFailed(
                VoiceAssistantCommand(
                    text = "remind me to take medicine at 7 pm",
                    deviceAction =
                        VoiceAssistantDeviceAction.CreateReminder(
                            title = "take medicine",
                            triggerAtEpochMillis = System.currentTimeMillis() + 60_000L,
                        ),
                ),
            )

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "No local calendar app is available on this device."
                }
            assertTrue(state.popupVisible)
            assertEquals("remind me to take medicine at 7 pm", state.lastTranscript)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
        }

    @Test
    fun `commandLaunchFailed reports unavailable local device action`() =
        runTest {
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                )

            viewModel.openPopup()
            viewModel.commandLaunchFailed(
                VoiceAssistantCommand(
                    text = "go home",
                    localDeviceAction = DeviceAction.PressGlobal(2), // GLOBAL_ACTION_HOME
                ),
            )

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Unable to run the Android navigation action."
                }
            assertTrue(state.popupVisible)
            assertEquals("go home", state.lastTranscript)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
        }

    @Test
    fun `commandLaunchFailed reports unavailable UI agent task`() =
        runTest {
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                )

            viewModel.openPopup()
            viewModel.commandLaunchFailed(
                VoiceAssistantCommand(
                    text = "message Alex saying hi",
                    uiAgentGoal =
                        UiAgentGoal.SendMessage(
                            recipient = "Alex",
                            message = "hi",
                        ),
                ),
            )

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Unable to complete the phone messaging task right now."
                }
            assertTrue(state.popupVisible)
            assertEquals("message Alex saying hi", state.lastTranscript)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
        }

    @Test
    fun `commandLaunchFailed prefers explicit runtime failure message`() =
        runTest {
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                )

            viewModel.openPopup()
            viewModel.commandLaunchFailed(
                VoiceAssistantCommand(
                    text = "message Alex saying hi",
                    uiAgentGoal =
                        UiAgentGoal.SendMessage(
                            recipient = "Alex",
                            message = "hi",
                        ),
                ),
                failureMessage = "Expected WhatsApp search results to stay visible.",
            )

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Expected WhatsApp search results to stay visible."
                }
            assertTrue(state.popupVisible)
            assertEquals("message Alex saying hi", state.lastTranscript)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
        }

    @Test
    fun `commandLaunchProgress keeps popup open and surfaces runtime progress`() =
        runTest {
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                )

            viewModel.openPopup()
            viewModel.commandLaunchProgress(
                VoiceAssistantCommand(
                    text = "message Alex saying hi",
                    uiAgentGoal =
                        UiAgentGoal.SendMessage(
                            recipient = "Alex",
                            message = "hi",
                        ),
                ),
                progressMessage = "Opened WhatsApp.",
            )

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Opened WhatsApp."
                }
            assertTrue(state.popupVisible)
            assertEquals("message Alex saying hi", state.lastTranscript)
            assertEquals(VoiceAssistantSessionState.Processing, state.sessionState)
        }

    @Test
    fun `runCommandLaunch surfaces launcher progress then speaks successful final result`() =
        runTest {
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechSynthesizer = synthesizer,
                )
            val finishLaunch = CompletableDeferred<Unit>()
            val command =
                VoiceAssistantCommand(
                    text = "message Alex saying hi on whatsapp",
                    uiAgentGoal =
                        UiAgentGoal.SendMessage(
                            recipient = "Alex",
                            message = "hi",
                            targetPackageName = "com.whatsapp",
                        ),
            )

            viewModel.openPopup()
            viewModel.runCommandLaunch(
                command = command,
                launchCommand = { _, onProgress ->
                    onProgress("Opening WhatsApp.")
                    finishLaunch.await()
                    VoiceAssistantCommandLaunchResult(
                        success = true,
                        finalMessage = "Message sent.",
                    )
                },
            )

            val progressState =
                viewModel.uiState.first {
                    it.statusMessage == "Opening WhatsApp."
                }
            assertEquals(VoiceAssistantSessionState.Processing, progressState.sessionState)

            finishLaunch.complete(Unit)
            val finalState =
                viewModel.uiState.first {
                    it.statusMessage == "Message sent." &&
                        it.sessionState == VoiceAssistantSessionState.Idle
                }
            assertTrue(finalState.popupVisible)
            assertEquals("message Alex saying hi on whatsapp", finalState.lastTranscript)
            assertEquals(listOf("Message sent."), synthesizer.spokenTexts)
        }

    @Test
    fun `closePopup does not cancel active device control command launch`() =
        runTest {
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechSynthesizer = synthesizer,
                )
            val launchStarted = CompletableDeferred<Unit>()
            val finishLaunch = CompletableDeferred<Unit>()
            var launchCancelled = false
            val command =
                VoiceAssistantCommand(
                    text = "message Alex saying hi on whatsapp",
                    uiAgentGoal =
                        UiAgentGoal.SendMessage(
                            recipient = "Alex",
                            message = "hi",
                            targetPackageName = "com.whatsapp",
                        ),
                )

            viewModel.openPopup()
            viewModel.runCommandLaunch(
                command = command,
                launchCommand = { _, onProgress ->
                    launchStarted.complete(Unit)
                    onProgress("Opening WhatsApp.")
                    try {
                        finishLaunch.await()
                    } catch (error: CancellationException) {
                        launchCancelled = true
                        throw error
                    }
                    VoiceAssistantCommandLaunchResult(
                        success = true,
                        finalMessage = "Message sent.",
                    )
                },
            )
            launchStarted.await()

            viewModel.closePopup()
            testScheduler.runCurrent()

            assertFalse(launchCancelled)

            finishLaunch.complete(Unit)
            val finalState =
                viewModel.uiState.first {
                    it.statusMessage == "Message sent." &&
                        it.sessionState == VoiceAssistantSessionState.Idle
                }
            assertTrue(finalState.popupVisible)
            assertEquals(listOf("Message sent."), synthesizer.spokenTexts)
        }

    @Test
    fun `runCommandLaunch speaks failed device control final result`() =
        runTest {
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechSynthesizer = synthesizer,
                )
            val command =
                VoiceAssistantCommand(
                    text = "message Alex saying hi on whatsapp",
                    uiAgentGoal =
                        UiAgentGoal.SendMessage(
                            recipient = "Alex",
                            message = "hi",
                            targetPackageName = "com.whatsapp",
                        ),
                )

            viewModel.openPopup()
            viewModel.runCommandLaunch(
                command = command,
                launchCommand = { _, _ ->
                    VoiceAssistantCommandLaunchResult(
                        success = false,
                        failureMessage = "WhatsApp search field was not visible.",
                        finalMessage = "WhatsApp search field was not visible.",
                    )
                },
            )

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "WhatsApp search field was not visible." &&
                        it.sessionState == VoiceAssistantSessionState.Idle
                }
            assertTrue(state.popupVisible)
            assertEquals("message Alex saying hi on whatsapp", state.lastTranscript)
            assertEquals(listOf("WhatsApp search field was not visible."), synthesizer.spokenTexts)
        }

    @Test
    fun `cancelAssistantTurn cancels active command launch and stops speech`() =
        runTest {
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    localSpeechSynthesizer = synthesizer,
                )
            val launchStarted = CompletableDeferred<Unit>()
            val command =
                VoiceAssistantCommand(
                    text = "message Alex saying hi on whatsapp",
                    uiAgentGoal =
                        UiAgentGoal.SendMessage(
                            recipient = "Alex",
                            message = "hi",
                            targetPackageName = "com.whatsapp",
                        ),
                )

            viewModel.openPopup()
            viewModel.runCommandLaunch(
                command = command,
                launchCommand = { _, _ ->
                    launchStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    VoiceAssistantCommandLaunchResult(
                        success = true,
                        finalMessage = "Message sent.",
                    )
                },
            )
            launchStarted.await()

            viewModel.cancelAssistantTurn()
            testScheduler.advanceUntilIdle()

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Voice request cancelled."
                }
            assertEquals("Voice request cancelled.", state.statusMessage)
            assertEquals(VoiceAssistantSessionState.Idle, state.sessionState)
            assertEquals(emptyList<String>(), synthesizer.spokenTexts)
            assertTrue(synthesizer.stopCalls >= 1)
        }

    @Test
    fun `deleteVoice removes selected voice and stops speech playback`() =
        runTest {
            val repository = installedVoiceRepository()
            val storage = LocalVoiceStorage(storageRoot = File(tempDir, "private"))
            val synthesizer = RecordingLocalSpeechSynthesizer()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = repository,
                    localVoiceStorage = storage,
                    localSpeechSynthesizer = synthesizer,
                )

            viewModel.deleteVoice("en-calm-guide")

            val state =
                viewModel.uiState.first {
                    it.statusMessage == "Calm Guide deleted from this phone."
                }
            assertEquals(VoiceAssistantSessionState.MissingVoice, state.sessionState)
            assertEquals(null, repository.selectedVoiceId.value)
            assertTrue(synthesizer.stopCalls >= 1)
        }

    @Test
    fun `setVoicePerformanceMode persists assistant speech mode`() =
        runTest {
            val preferences = InMemoryVoiceOutputPreferences()
            val viewModel =
                VoiceAssistantViewModel(
                    voiceCatalogRepository = installedVoiceRepository(),
                    voiceOutputPreferences = preferences,
                )

            viewModel.setVoicePerformanceMode(VoicePerformanceMode.FAST)

            assertEquals(VoicePerformanceMode.FAST, viewModel.voicePerformanceMode.value)
            assertEquals(VoicePerformanceMode.FAST, preferences.performanceMode.value)
            assertEquals(
                "Speech mode set to Fast.",
                viewModel
                    .uiState
                    .first { it.statusMessage == "Speech mode set to Fast." }
                    .statusMessage,
            )
        }

    private fun installedVoiceRepository(): LocalVoiceCatalogRepository =
        LocalVoiceCatalogRepository().apply {
            markInstalled(
                voiceId = "en-calm-guide",
                modelUri = "android-tts://en-calm-guide",
            )
        }

    private fun readyWakeupStatus(): VoiceWakeupDetectorStatus =
        VoiceWakeupDetectorStatus(
            available = true,
            foregroundServiceReady = true,
            requiresRecordAudioPermission = true,
            message = READY_WAKEUP_MESSAGE,
        )

    private class FakeLocalSpeechRecognizer(
        private val events: List<SpeechRecognitionEvent>,
        initialStatus: LocalSpeechEngineStatus = LocalSpeechEngineStatus.Ready,
    ) : LocalSpeechRecognizer {
        override val status = MutableStateFlow(initialStatus)
        var listenCalls = 0
            private set
        var stopCalls = 0
            private set

        override fun listen(): Flow<SpeechRecognitionEvent> =
            flow {
                listenCalls += 1
                events.forEach { event -> emit(event) }
            }

        override fun stop() {
            stopCalls += 1
        }
    }

    private class BlockingLocalSpeechRecognizer : LocalSpeechRecognizer {
        override val status = MutableStateFlow(LocalSpeechEngineStatus.Ready)
        val started = CompletableDeferred<Unit>()
        var listenCalls = 0
            private set
        var stopCalls = 0
            private set

        override fun listen(): Flow<SpeechRecognitionEvent> =
            flow {
                listenCalls += 1
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }

        override fun stop() {
            stopCalls += 1
        }
    }

    private class RecordingVoiceAssistantConversation(
        private val result: VoiceAssistantConversationResult =
            VoiceAssistantConversationResult.Success("Done."),
    ) : VoiceAssistantConversation {
        val transcripts = mutableListOf<String>()

        override suspend fun submitTranscript(transcript: String): VoiceAssistantConversationResult {
            transcripts += transcript
            return result
        }
    }

    private class EarlySentenceVoiceAssistantConversation : VoiceAssistantConversation {
        val finish = CompletableDeferred<Unit>()

        override suspend fun submitTranscript(transcript: String): VoiceAssistantConversationResult =
            submitTranscript(transcript, onNextSpokenSentence = {})

        override suspend fun submitTranscript(
            transcript: String,
            onNextSpokenSentence: (String) -> Unit,
        ): VoiceAssistantConversationResult {
            onNextSpokenSentence("First answer.")
            onNextSpokenSentence("Second answer.")
            finish.await()
            return VoiceAssistantConversationResult.Success("First answer. Second answer.")
        }
    }

    private class BlockingVoiceAssistantConversation : VoiceAssistantConversation {
        val started = CompletableDeferred<Unit>()
        var cancelCalls = 0
            private set

        override suspend fun submitTranscript(transcript: String): VoiceAssistantConversationResult =
            submitTranscript(transcript, onNextSpokenSentence = {})

        override suspend fun submitTranscript(
            transcript: String,
            onNextSpokenSentence: (String) -> Unit,
        ): VoiceAssistantConversationResult {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            return VoiceAssistantConversationResult.Success("Done.")
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private class RecordingLocalSpeechSynthesizer(
        private val result: SpeechSynthesisResult = SpeechSynthesisResult.Completed,
    ) : LocalSpeechSynthesizer {
        override val status = MutableStateFlow(LocalSpeechEngineStatus.Ready)
        val preparedVoiceIds = mutableListOf<String>()
        val spokenTexts = mutableListOf<String>()
        var stopCalls = 0
            private set

        override suspend fun prepare(voice: VoiceModel): SpeechSynthesisResult {
            preparedVoiceIds += voice.id
            return SpeechSynthesisResult.Completed
        }

        override suspend fun speak(
            text: String,
            voice: VoiceModel,
        ): SpeechSynthesisResult {
            spokenTexts += text
            return result
        }

        override fun stop() {
            stopCalls += 1
        }
    }

    private class BlockingLocalSpeechSynthesizer : LocalSpeechSynthesizer {
        override val status = MutableStateFlow(LocalSpeechEngineStatus.Ready)
        val prepareStarted = CompletableDeferred<Unit>()
        val prepareCancelled = CompletableDeferred<Unit>()
        var stopCalls = 0
            private set

        override suspend fun prepare(voice: VoiceModel): SpeechSynthesisResult {
            prepareStarted.complete(Unit)
            return try {
                CompletableDeferred<SpeechSynthesisResult>().await()
            } catch (error: CancellationException) {
                prepareCancelled.complete(Unit)
                throw error
            }
        }

        override suspend fun speak(
            text: String,
            voice: VoiceModel,
        ): SpeechSynthesisResult = SpeechSynthesisResult.Completed

        override fun stop() {
            stopCalls += 1
        }
    }

    private class FakeVoiceContactLookup(
        private val result: VoiceContactLookupResult,
    ) : VoiceContactLookup {
        val contactNames = mutableListOf<String>()

        override suspend fun findPhoneDialUri(contactName: String): VoiceContactLookupResult {
            contactNames += contactName
            return result
        }
    }

    private class FakeVoiceWakeupDetector(
        initialStatus: VoiceWakeupDetectorStatus,
    ) : VoiceWakeupDetector {
        override val status = MutableStateFlow(initialStatus)
        var startCalls = 0
            private set

        override suspend fun startForegroundWakeup(): VoiceWakeupStartResult {
            startCalls += 1
            return VoiceWakeupStartResult.Started
        }

        override suspend fun stopForegroundWakeup() = Unit
    }

    private class RecordingVoiceWakeupServiceController(
        private val recordAudioGranted: Boolean,
        private val startResult: VoiceWakeupServiceCommandResult = VoiceWakeupServiceCommandResult.Accepted,
    ) : VoiceWakeupServiceController {
        var startCalls = 0
            private set
        var stopCalls = 0
            private set

        override fun hasRecordAudioPermission(): Boolean = recordAudioGranted

        override fun startWakeup(): VoiceWakeupServiceCommandResult {
            startCalls += 1
            return startResult
        }

        override fun stopWakeup(): VoiceWakeupServiceCommandResult {
            stopCalls += 1
            return VoiceWakeupServiceCommandResult.Accepted
        }
    }

    private companion object {
        const val READY_WAKEUP_MESSAGE =
            "Local wake-word detector is available for foreground service startup."
        const val STARTED_WAKEUP_MESSAGE =
            "Wake-up service requested. Local wake-word detector is available for foreground service startup."
    }
}

private fun clearViewModel(viewModel: VoiceAssistantViewModel) {
    val method = VoiceAssistantViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(viewModel)
}
