/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings.googleworkspace

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.google.GoogleWorkspaceAuthManager
import com.zeroclaw.android.service.sandbox.LinuxSandboxManager
import com.zeroclaw.android.service.sandbox.SandboxState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GwsSettingsViewModel"

val ALL_GWS_SERVICES = listOf(
    "gmail", "drive", "calendar", "sheets", "docs", "slides",
    "tasks", "people", "chat", "classroom", "forms",
    "meet", "events",
)

val GWS_SERVICE_LABELS = mapOf(
    "gmail" to "Gmail",
    "drive" to "Drive",
    "calendar" to "Calendar",
    "sheets" to "Sheets",
    "docs" to "Docs",
    "slides" to "Slides",
    "tasks" to "Tasks",
    "people" to "People",
    "chat" to "Chat",
    "classroom" to "Classroom",
    "forms" to "Forms",
    "meet" to "Meet",
    "events" to "Events",
)

sealed interface InstallState {
    data object Checking : InstallState
    data object Installing : InstallState
    data object Ready : InstallState
    data class Error(val message: String) : InstallState
}

data class GwsSettingsState(
    val installState: InstallState = InstallState.Checking,
    val enabled: Boolean = false,
    val allowedServices: Set<String> = ALL_GWS_SERVICES.toSet(),
    val auditLog: Boolean = false,
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val testResult: TestResult = TestResult.Idle,
    val saving: Boolean = false,
)

sealed interface TestResult {
    data object Idle : TestResult
    data object Running : TestResult
    data class Success(val message: String) : TestResult
    data class Failure(val error: String) : TestResult
}

@Suppress("TooManyFunctions")
class GoogleWorkspaceViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val authManager = GoogleWorkspaceAuthManager(application)
    private val app = application as ZeroClawApplication
    private val settingsRepository = app.settingsRepository
    private val sandboxManager = app.linuxSandboxManager
    private val _state = MutableStateFlow(GwsSettingsState())
    val state: StateFlow<GwsSettingsState> = _state.asStateFlow()

    init {
        loadSettings()
        checkAndInstallCli()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val servicesRaw = settings.googleWorkspaceAllowedServices
                .split(",")
                .map { it.trim() }
                .filter { it in ALL_GWS_SERVICES }
                .ifEmpty { ALL_GWS_SERVICES }
                .toSet()
            _state.update {
                it.copy(
                    enabled = settings.googleWorkspaceEnabled,
                    allowedServices = servicesRaw,
                    auditLog = settings.googleWorkspaceAuditLog,
                    isSignedIn = authManager.isSignedIn,
                    accountEmail = authManager.getAccountEmail(),
                )
            }
        }
    }

    private fun checkAndInstallCli() {
        viewModelScope.launch {
            _state.update { it.copy(installState = InstallState.Checking) }

            // Wait for sandbox to be ready before checking for CLI
            while (sandboxManager.state.value !is SandboxState.Ready) {
                val s = sandboxManager.state.value
                if (s is SandboxState.Error) {
                    _state.update { it.copy(installState = InstallState.Error(s.message)) }
                    return@launch
                }
                delay(250)
            }

            triggerInstallIfNeeded()
        }
    }

    private suspend fun triggerInstallIfNeeded() {
        if (sandboxManager.isGoogleWorkspaceCliInstalled()) {
            _state.update { it.copy(installState = InstallState.Ready) }
            return
        }
        _state.update { it.copy(installState = InstallState.Installing) }
        sandboxManager.installGoogleWorkspaceCli()
        // Poll until install finishes or errors
        while (true) {
            val s = sandboxManager.state.value
            when (s) {
                is SandboxState.Ready -> {
                    if (sandboxManager.isGoogleWorkspaceCliInstalled()) {
                        _state.update { it.copy(installState = InstallState.Ready) }
                    } else {
                        _state.update { it.copy(installState = InstallState.Error("gws CLI not found after install")) }
                    }
                    return
                }
                is SandboxState.Error -> {
                    _state.update { it.copy(installState = InstallState.Error(s.message)) }
                    return
                }
                else -> delay(250)
            }
        }
    }

    fun retryInstall() {
        viewModelScope.launch {
            checkAndInstallCli()
        }
    }

    fun toggleEnabled() {
        _state.update { it.copy(enabled = !it.enabled) }
    }

    fun toggleService(service: String) {
        _state.update { s ->
            val newServices = if (service in s.allowedServices) {
                s.allowedServices - service
            } else {
                s.allowedServices + service
            }
            s.copy(allowedServices = newServices)
        }
    }

    fun selectAllServices() {
        _state.update { it.copy(allowedServices = ALL_GWS_SERVICES.toSet()) }
    }

    fun deselectAllServices() {
        _state.update { it.copy(allowedServices = emptySet()) }
    }

    fun toggleAuditLog() {
        _state.update { it.copy(auditLog = !it.auditLog) }
    }

    fun getSignInIntent(): Intent = authManager.getSignInIntent()

    fun handleSignInResult(data: Intent?) {
        viewModelScope.launch {
            val success = authManager.handleSignInResult(data)
            _state.update {
                it.copy(
                    isSignedIn = authManager.isSignedIn,
                    accountEmail = authManager.getAccountEmail(),
                )
            }
            Log.d(TAG, "Sign-in result: success=$success")
        }
    }

    fun signOut() {
        authManager.signOut {
            _state.update {
                it.copy(isSignedIn = false, accountEmail = null)
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(testResult = TestResult.Running) }
            val result = withContext(Dispatchers.IO) { executeTestCommand() }
            _state.update { it.copy(testResult = result) }
        }
    }

    fun save() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            settingsRepository.setGoogleWorkspaceEnabled(_state.value.enabled)
            settingsRepository.setGoogleWorkspaceAllowedServices(_state.value.allowedServices.joinToString(","))
            settingsRepository.setGoogleWorkspaceAuditLog(_state.value.auditLog)
            app.daemonBridge.markRestartRequired()
            _state.update { it.copy(saving = false) }
        }
    }

    fun reset() {
        _state.update {
            it.copy(
                enabled = false,
                allowedServices = ALL_GWS_SERVICES.toSet(),
                auditLog = false,
            )
        }
    }

    private fun executeTestCommand(): TestResult {
        return try {
            val bridgePort = 8484
            val url = URL("http://127.0.0.1:$bridgePort/execute/gws")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 5000
                readTimeout = 15000
            }

            val body = JSONObject().apply {
                put("service", "drive")
                put("resource", "files")
                put("method", "list")
                put("params", JSONObject().put("pageSize", "1"))
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseBody = BufferedReader(InputStreamReader(stream)).use { it.readText() }

            val json = JSONObject(responseBody)
            val success = json.optBoolean("success", false)

            if (success) {
                TestResult.Success("Connected. Sandbox bridge is reachable.")
            } else {
                val error = json.optString("error", "Unknown error")
                TestResult.Failure(error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test connection failed", e)
            TestResult.Failure("Cannot reach sandbox bridge: ${e.message ?: "unknown"}")
        }
    }
}
