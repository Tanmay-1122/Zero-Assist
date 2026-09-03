/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("LocalSpeechWakeupDetector")
class LocalSpeechWakeupDetectorTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun `ready local recognizer reports foreground wakeup availability`() =
        runTest(dispatcher) {
            val detector =
                LocalSpeechWakeupDetector(
                    localSpeechRecognizer =
                        FakeLocalSpeechRecognizer(
                            status = LocalSpeechEngineStatus.Ready,
                        ),
                    scope = CoroutineScope(dispatcher),
                    dispatcher = dispatcher,
                )

            val status = detector.status.first()

            assertTrue(status.available)
            assertTrue(status.foregroundServiceReady)
            assertEquals("Local wake phrase detector is ready.", status.message)
        }

    @Test
    fun `startForegroundWakeup emits wake event when local transcript contains wake phrase`() =
        runTest(dispatcher) {
            val recognizer =
                FakeLocalSpeechRecognizer(
                    status = LocalSpeechEngineStatus.Ready,
                    events = listOf(SpeechRecognitionEvent.FinalTranscript("hey zero open")),
                )
            val detector =
                LocalSpeechWakeupDetector(
                    localSpeechRecognizer = recognizer,
                    scope = CoroutineScope(dispatcher),
                    dispatcher = dispatcher,
                )
            val wakeEvent =
                async(start = CoroutineStart.UNDISPATCHED) {
                    detector.wakeEvents.first()
                }

            val result = detector.startForegroundWakeup()

            assertEquals(VoiceWakeupStartResult.Started, result)
            wakeEvent.await()
            assertEquals(1, recognizer.listenCalls)
            assertTrue(recognizer.stopCalls >= 1)
        }

    @Test
    fun `startForegroundWakeup refuses missing local speech model`() =
        runTest(dispatcher) {
            val detector =
                LocalSpeechWakeupDetector(
                    localSpeechRecognizer =
                        FakeLocalSpeechRecognizer(
                            status = LocalSpeechEngineStatus.MissingModel,
                        ),
                    scope = CoroutineScope(dispatcher),
                    dispatcher = dispatcher,
                )

            val result = detector.startForegroundWakeup()

            assertEquals(
                VoiceWakeupStartResult.Unavailable(
                    "Install an offline English speech recognition service on this phone.",
                ),
                result,
            )
        }

    @Test
    fun `recognizer errors wait before restarting wakeup listening`() =
        runTest(dispatcher) {
            val recognizer =
                FakeLocalSpeechRecognizer(
                    status = LocalSpeechEngineStatus.Ready,
                    events = listOf(SpeechRecognitionEvent.Error("No speech was recognized.")),
                )
            val detector =
                LocalSpeechWakeupDetector(
                    localSpeechRecognizer = recognizer,
                    scope = CoroutineScope(dispatcher),
                    dispatcher = dispatcher,
                )

            detector.startForegroundWakeup()

            assertEquals(1, recognizer.listenCalls)
            advanceTimeBy(1_499)
            runCurrent()
            assertEquals(1, recognizer.listenCalls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, recognizer.listenCalls)
            detector.stopForegroundWakeup()
        }

    private class FakeLocalSpeechRecognizer(
        status: LocalSpeechEngineStatus,
        private val events: List<SpeechRecognitionEvent> = emptyList(),
    ) : LocalSpeechRecognizer {
        override val status = MutableStateFlow(status)
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
}
