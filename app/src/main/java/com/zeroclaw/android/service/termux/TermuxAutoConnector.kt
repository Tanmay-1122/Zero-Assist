/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Orchestrates the Termux auto-connect sequence:
 * 1. Check if Termux is installed
 * 2. Request RUN_COMMAND permission if needed
 * 3. Ensure bridge is started
 * 4. Run a test command to verify end-to-end
 */
class TermuxAutoConnector(
    private val context: Context,
    private val probe: TermuxRuntimeProbe,
    private val supervisor: TermuxBridgeSupervisor,
    private val healthClient: TermuxHealthClient,
) {
    sealed class ConnectionState {
        data object Checking : ConnectionState()
        data object NotInstalled : ConnectionState()
        data object PermissionNeeded : ConnectionState()
        data object StartingBridge : ConnectionState()
        data object TestingConnection : ConnectionState()
        data class Connected(val health: TermuxHealthSnapshot) : ConnectionState()
        data class Failed(val reason: String) : ConnectionState()
    }

    fun observeConnection(): Flow<ConnectionState> = flow {
        emit(ConnectionState.Checking)
        val snapshot = probe.snapshot()

        when (snapshot.packageState.availability) {
            TermuxPackageAvailability.NOT_INSTALLED,
            TermuxPackageAvailability.NOT_VISIBLE,
            -> {
                emit(ConnectionState.NotInstalled)
                return@flow
            }
            TermuxPackageAvailability.INSTALLED -> { /* continue */ }
        }

        when (snapshot.permissionState.availability) {
            TermuxPermissionAvailability.DENIED,
            TermuxPermissionAvailability.UNKNOWN,
            -> {
                emit(ConnectionState.PermissionNeeded)
                return@flow
            }
            TermuxPermissionAvailability.GRANTED -> { /* continue */ }
        }

        emit(ConnectionState.StartingBridge)
        supervisor.ensureStarted()

        emit(ConnectionState.TestingConnection)
        val health = healthClient.checkHealth()
        if (health.status == TermuxHealthStatus.READY) {
            emit(ConnectionState.Connected(health))
        } else {
            emit(ConnectionState.Failed(health.reason))
        }
    }

    fun requestPermission(activity: Activity) {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        activity.startActivity(intent)
    }

    fun openFroidInstall() {
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://f-droid.org/en/packages/com.termux/"),
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openPermissionSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    fun startAutoConnect(): Flow<ConnectionState> = observeConnection()
}
