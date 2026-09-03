/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Reconciles persisted wake-up preference with the guarded foreground service on app start. */
class VoiceWakeupStartupCoordinator(
    private val voiceWakeupPreferences: VoiceWakeupPreferences,
    private val voiceWakeupDetectorProvider: () -> VoiceWakeupDetector,
    private val voiceWakeupServiceController: VoiceWakeupServiceController,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DefaultLifecycleObserver {
    private var reconcileJob: Job? = null
    private val voiceWakeupDetectorHolder = lazy(voiceWakeupDetectorProvider)
    private val voiceWakeupDetector: VoiceWakeupDetector
        get() = voiceWakeupDetectorHolder.value

    constructor(
        voiceWakeupPreferences: VoiceWakeupPreferences,
        voiceWakeupDetector: VoiceWakeupDetector,
        voiceWakeupServiceController: VoiceWakeupServiceController,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        voiceWakeupPreferences = voiceWakeupPreferences,
        voiceWakeupDetectorProvider = { voiceWakeupDetector },
        voiceWakeupServiceController = voiceWakeupServiceController,
        scope = scope,
        dispatcher = dispatcher,
    )

    override fun onStart(owner: LifecycleOwner) {
        reconcileJob?.cancel()
        reconcileJob =
            scope.launch(dispatcher) {
                var detectorStatusJob: Job? = null
                voiceWakeupPreferences.wakeupRequested
                    .distinctUntilChanged()
                    .collect { requested ->
                        detectorStatusJob?.cancel()
                        detectorStatusJob = null
                        if (!requested) {
                            reconcileSnapshot(
                                requested = false,
                                status = VoiceWakeupDetectorStatus.Unavailable,
                            )
                            return@collect
                        }
                        detectorStatusJob =
                            launch {
                                voiceWakeupDetector.status
                                    .distinctUntilChanged()
                                    .collect { status ->
                                        reconcileSnapshot(
                                            requested = true,
                                            status = status,
                                        )
                                    }
                            }
                    }
                detectorStatusJob?.cancel()
            }
    }

    override fun onStop(owner: LifecycleOwner) {
        reconcileJob?.cancel()
        reconcileJob = null
    }

    suspend fun reconcile(): VoiceWakeupStartupReconcileResult =
        withContext(dispatcher) {
            val requested =
                withTimeoutOrNull(PREFERENCE_TIMEOUT_MS) {
                    runCatching { voiceWakeupPreferences.wakeupRequested.first() }.getOrNull()
                } ?: return@withContext VoiceWakeupStartupReconcileResult.Blocked(
                    PREFERENCE_UNAVAILABLE_MESSAGE,
                )

            if (!requested) {
                return@withContext reconcileSnapshot(
                    requested = false,
                    status = VoiceWakeupDetectorStatus.Unavailable,
                )
            }

            val status =
                VoiceWakeupForegroundStartGuard.currentStatusOrUnavailable(voiceWakeupDetector)
            return@withContext reconcileSnapshot(
                requested = true,
                status = status,
            )
        }

    private fun reconcileSnapshot(
        requested: Boolean,
        status: VoiceWakeupDetectorStatus,
    ): VoiceWakeupStartupReconcileResult {
        if (!requested) {
            voiceWakeupServiceController.stopWakeup()
            serviceStartRequested = false
            return VoiceWakeupStartupReconcileResult.NotRequested
        }

        return when (
            val decision =
                VoiceWakeupForegroundStartGuard.evaluate(
                    status = status,
                    hasRecordAudioPermission =
                        voiceWakeupServiceController.hasRecordAudioPermission(),
                )
        ) {
            VoiceWakeupForegroundStartDecision.Ready -> {
                if (serviceStartRequested) {
                    VoiceWakeupStartupReconcileResult.Started
                } else {
                    when (val result = voiceWakeupServiceController.startWakeup()) {
                        VoiceWakeupServiceCommandResult.Accepted -> {
                            serviceStartRequested = true
                            VoiceWakeupStartupReconcileResult.Started
                        }
                        is VoiceWakeupServiceCommandResult.Failed -> {
                            serviceStartRequested = false
                            VoiceWakeupStartupReconcileResult.StartFailed(result.message)
                        }
                    }
                }
            }
            is VoiceWakeupForegroundStartDecision.Blocked -> {
                voiceWakeupServiceController.stopWakeup()
                serviceStartRequested = false
                VoiceWakeupStartupReconcileResult.Blocked(decision.message)
            }
        }
    }

    private var serviceStartRequested = false

    private companion object {
        const val PREFERENCE_TIMEOUT_MS = 1_000L
        const val PREFERENCE_UNAVAILABLE_MESSAGE =
            "Wake-up preference is unavailable during app startup."
    }
}

sealed interface VoiceWakeupStartupReconcileResult {
    data object Started : VoiceWakeupStartupReconcileResult

    data object NotRequested : VoiceWakeupStartupReconcileResult

    data class Blocked(
        val message: String,
    ) : VoiceWakeupStartupReconcileResult

    data class StartFailed(
        val message: String,
    ) : VoiceWakeupStartupReconcileResult
}
