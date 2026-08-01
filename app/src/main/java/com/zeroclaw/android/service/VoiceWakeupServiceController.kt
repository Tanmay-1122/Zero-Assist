/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Starts and stops the guarded Android foreground service for local wake-word listening. */
interface VoiceWakeupServiceController {
    fun hasRecordAudioPermission(): Boolean

    fun startWakeup(): VoiceWakeupServiceCommandResult

    fun stopWakeup(): VoiceWakeupServiceCommandResult
}

sealed interface VoiceWakeupServiceCommandResult {
    data object Accepted : VoiceWakeupServiceCommandResult

    data class Failed(
        val message: String,
    ) : VoiceWakeupServiceCommandResult
}

/** Test/default controller used when no Android runtime service boundary is available. */
object MissingVoiceWakeupServiceController : VoiceWakeupServiceController {
    override fun hasRecordAudioPermission(): Boolean = false

    override fun startWakeup(): VoiceWakeupServiceCommandResult =
        VoiceWakeupServiceCommandResult.Failed("Wake-up service is unavailable.")

    override fun stopWakeup(): VoiceWakeupServiceCommandResult =
        VoiceWakeupServiceCommandResult.Accepted
}

class AndroidVoiceWakeupServiceController(
    context: Context,
) : VoiceWakeupServiceController {
    private val appContext = context.applicationContext

    override fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun startWakeup(): VoiceWakeupServiceCommandResult =
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, VoiceWakeupForegroundService::class.java).apply {
                    action = VoiceWakeupForegroundService.ACTION_START
                },
            )
        }.fold(
            onSuccess = { VoiceWakeupServiceCommandResult.Accepted },
            onFailure = {
                VoiceWakeupServiceCommandResult.Failed(
                    it.message ?: "Android blocked wake-up service startup.",
                )
            },
        )

    override fun stopWakeup(): VoiceWakeupServiceCommandResult =
        runCatching {
            appContext.stopService(
                Intent(appContext, VoiceWakeupForegroundService::class.java),
            )
        }.fold(
            onSuccess = { VoiceWakeupServiceCommandResult.Accepted },
            onFailure = {
                VoiceWakeupServiceCommandResult.Failed(
                    it.message ?: "Android blocked wake-up service shutdown.",
                )
            },
        )
}
