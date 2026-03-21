/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.droidrun

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.data.DroidRunSettings
import com.zeroclaw.android.data.SecurePrefsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the DroidRun configuration screen.
 */
data class DroidRunUiState(
    /** The configured server URL. */
    val serverUrl: String = "",
    /** The configured API key. */
    val apiKey: String = "",
    /** Whether a save operation is in progress. */
    val isSaving: Boolean = false,
    /** Error message from the last save attempt, if any. */
    val saveError: String? = null,
    /** Whether the last save attempt was successful. */
    val saveSuccess: Boolean = false,
)

/**
 * ViewModel for the DroidRun configuration screen.
 */
class DroidRunViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val securePrefs =
        SecurePrefsProvider.create(application, DroidRunSettings.PREFS_NAME).first
    private val daemonBridge = app.daemonBridge

    private val _uiState = MutableStateFlow(DroidRunUiState())

    /** Observes the current [DroidRunUiState]. */
    val uiState: StateFlow<DroidRunUiState> = _uiState.asStateFlow()

    init {
        _uiState.value =
            _uiState.value.copy(
                serverUrl =
                    securePrefs.getString(DroidRunSettings.KEY_SERVER_URL, "").orEmpty(),
                apiKey = securePrefs.getString(DroidRunSettings.KEY_API_KEY, "").orEmpty(),
            )
    }

    /**
     * Updates the server URL in the UI state.
     *
     * @param newUrl The new URL string.
     */
    fun onUrlChange(newUrl: String) {
        _uiState.value =
            _uiState.value.copy(
                serverUrl = newUrl,
                saveSuccess = false,
                saveError = null,
            )
    }

    /**
     * Updates the API key in the UI state.
     *
     * @param newKey The new API key string.
     */
    fun onApiKeyChange(newKey: String) {
        _uiState.value =
            _uiState.value.copy(
                apiKey = newKey,
                saveSuccess = false,
                saveError = null,
            )
    }

    /**
     * Saves the DroidRun server settings and marks the daemon for restart.
     */
    fun saveConfig() {
        val url = _uiState.value.serverUrl.trim()
        val apiKey = _uiState.value.apiKey.trim()

        if (url.isBlank()) {
            _uiState.value =
                _uiState.value.copy(saveError = "Server URL cannot be empty")
            return
        }

        _uiState.value =
            _uiState.value.copy(
                isSaving = true,
                saveError = null,
                saveSuccess = false,
            )
        viewModelScope.launch {
            try {
                securePrefs
                    .edit()
                    .putString(DroidRunSettings.KEY_SERVER_URL, url)
                    .putString(DroidRunSettings.KEY_API_KEY, apiKey.ifBlank { null })
                    .apply()

                daemonBridge.markRestartRequired()
                _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isSaving = false,
                        saveError = e.message ?: "Unknown error",
                    )
            }
        }
    }
}
