/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("VoiceWakeupStartupCoordinator")
class VoiceWakeupStartupCoordinatorTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun `reconcile starts wakeup service when persisted request is ready`() =
        runTest(dispatcher) {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val coordinator =
                coordinator(
                    preferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = true),
                    detector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    controller = controller,
                )

            val result = coordinator.reconcile()

            assertEquals(VoiceWakeupStartupReconcileResult.Started, result)
            assertEquals(1, controller.startCalls)
            assertEquals(0, controller.stopCalls)
        }

    @Test
    fun `reconcile stops wakeup service when persisted request is off`() =
        runTest(dispatcher) {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val coordinator =
                coordinator(
                    preferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = false),
                    detector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    controller = controller,
                )

            val result = coordinator.reconcile()

            assertEquals(VoiceWakeupStartupReconcileResult.NotRequested, result)
            assertEquals(0, controller.startCalls)
            assertEquals(1, controller.stopCalls)
        }

    @Test
    fun `reconcile does not construct wakeup detector when persisted request is off`() =
        runTest(dispatcher) {
            var detectorProviderCalls = 0
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val coordinator =
                VoiceWakeupStartupCoordinator(
                    voiceWakeupPreferences =
                        InMemoryVoiceWakeupPreferences(initialWakeupRequested = false),
                    voiceWakeupDetectorProvider = {
                        detectorProviderCalls += 1
                        FakeVoiceWakeupDetector(readyWakeupStatus())
                    },
                    voiceWakeupServiceController = controller,
                    scope = CoroutineScope(dispatcher),
                    dispatcher = dispatcher,
                )

            val result = coordinator.reconcile()

            assertEquals(VoiceWakeupStartupReconcileResult.NotRequested, result)
            assertEquals(0, detectorProviderCalls)
            assertEquals(1, controller.stopCalls)
        }

    @Test
    fun `reconcile blocks and stops wakeup service without microphone permission`() =
        runTest(dispatcher) {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = false)
            val coordinator =
                coordinator(
                    preferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = true),
                    detector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    controller = controller,
                )

            val result = coordinator.reconcile()

            assertTrue(result is VoiceWakeupStartupReconcileResult.Blocked)
            result as VoiceWakeupStartupReconcileResult.Blocked
            assertEquals("Microphone permission is required for wake-up mode.", result.message)
            assertEquals(0, controller.startCalls)
            assertEquals(1, controller.stopCalls)
        }

    @Test
    fun `reconcile reports service startup failure`() =
        runTest(dispatcher) {
            val coordinator =
                coordinator(
                    preferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = true),
                    detector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    controller =
                        RecordingVoiceWakeupServiceController(
                            recordAudioGranted = true,
                            startResult =
                                VoiceWakeupServiceCommandResult.Failed(
                                    "Android blocked wake-up service startup.",
                                ),
                        ),
                )

            val result = coordinator.reconcile()

            assertEquals(
                VoiceWakeupStartupReconcileResult.StartFailed(
                    "Android blocked wake-up service startup.",
                ),
                result,
            )
        }

    @Test
    fun `reconcile does not duplicate wakeup service start while already requested`() =
        runTest(dispatcher) {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val coordinator =
                coordinator(
                    preferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = true),
                    detector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    controller = controller,
                )

            coordinator.reconcile()
            val result = coordinator.reconcile()

            assertEquals(VoiceWakeupStartupReconcileResult.Started, result)
            assertEquals(1, controller.startCalls)
            assertEquals(0, controller.stopCalls)
        }

    @Test
    fun `reconcile stops wakeup service when detector becomes unavailable`() =
        runTest(dispatcher) {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val detector = FakeVoiceWakeupDetector(readyWakeupStatus())
            val coordinator =
                coordinator(
                    preferences = InMemoryVoiceWakeupPreferences(initialWakeupRequested = true),
                    detector = detector,
                    controller = controller,
                )

            coordinator.reconcile()
            detector.status.value = VoiceWakeupDetectorStatus.Unavailable
            val result = coordinator.reconcile()

            assertTrue(result is VoiceWakeupStartupReconcileResult.Blocked)
            assertEquals(1, controller.startCalls)
            assertEquals(1, controller.stopCalls)
        }

    @Test
    fun `reconcile fails closed when preference never emits`() =
        runTest(dispatcher) {
            val controller = RecordingVoiceWakeupServiceController(recordAudioGranted = true)
            val coordinator =
                coordinator(
                    preferences = EmptyVoiceWakeupPreferences,
                    detector = FakeVoiceWakeupDetector(readyWakeupStatus()),
                    controller = controller,
                )

            val result = coordinator.reconcile()

            assertTrue(result is VoiceWakeupStartupReconcileResult.Blocked)
            assertEquals(0, controller.startCalls)
        }

    private fun coordinator(
        preferences: VoiceWakeupPreferences,
        detector: VoiceWakeupDetector,
        controller: VoiceWakeupServiceController,
    ): VoiceWakeupStartupCoordinator =
        VoiceWakeupStartupCoordinator(
            voiceWakeupPreferences = preferences,
            voiceWakeupDetector = detector,
            voiceWakeupServiceController = controller,
            scope = CoroutineScope(dispatcher),
            dispatcher = dispatcher,
        )

    private fun readyWakeupStatus(): VoiceWakeupDetectorStatus =
        VoiceWakeupDetectorStatus(
            available = true,
            foregroundServiceReady = true,
            requiresRecordAudioPermission = true,
            message = "Local wake-word detector is available for foreground service startup.",
        )

    private class FakeVoiceWakeupDetector(
        initialStatus: VoiceWakeupDetectorStatus,
    ) : VoiceWakeupDetector {
        override val status = MutableStateFlow(initialStatus)

        override suspend fun startForegroundWakeup(): VoiceWakeupStartResult =
            VoiceWakeupStartResult.Started

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

    private object EmptyVoiceWakeupPreferences : VoiceWakeupPreferences {
        override val wakeupRequested: Flow<Boolean> = emptyFlow()

        override suspend fun setWakeupRequested(enabled: Boolean) = Unit
    }
}
