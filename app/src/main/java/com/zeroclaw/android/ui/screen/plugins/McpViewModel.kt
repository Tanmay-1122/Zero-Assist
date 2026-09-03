/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.CuratedMcpServers
import com.zeroclaw.android.model.McpServerEntry
import com.zeroclaw.android.model.McpTransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Connection status of an MCP server as reported by the daemon.
 */
enum class ServerStatus {
    /** Server connected and tools available. */
    CONNECTED,

    /** Server not connected or disabled. */
    DISCONNECTED,

    /** Last connection attempt failed. */
    ERROR,

    /** Status unknown (e.g. daemon not running). */
    UNKNOWN,
}

/**
 * ViewModel for the MCP management tab.
 *
 * Manages the list of user-configured MCP servers and persists them
 * through [SettingsRepository]. The actual daemon connection is handled
 * by the Rust layer on session init; this ViewModel only manages config.
 */
class McpViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app = application as ZeroClawApplication
    private val settingsRepository = app.settingsRepository

    private val _servers = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val servers: StateFlow<List<McpServerEntry>> = _servers.asStateFlow()

    private val _mcpEnabled = MutableStateFlow(false)
    val mcpEnabled: StateFlow<Boolean> = _mcpEnabled.asStateFlow()

    private val _deferredLoading = MutableStateFlow(false)
    val deferredLoading: StateFlow<Boolean> = _deferredLoading.asStateFlow()

    private val _connectionStatus = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, ServerStatus>> = _connectionStatus.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                _mcpEnabled.value = settings.mcpEnabled
                _deferredLoading.value = settings.mcpDeferredLoading
                _servers.value = parseServers(settings.mcpServersJson)
            }
        }
    }

    fun toggleMcpEnabled() {
        val newValue = !_mcpEnabled.value
        _mcpEnabled.value = newValue
        viewModelScope.launch {
            settingsRepository.setMcpEnabled(newValue)
        }
    }

    fun toggleDeferredLoading() {
        val newValue = !_deferredLoading.value
        _deferredLoading.value = newValue
        viewModelScope.launch {
            settingsRepository.setMcpDeferredLoading(newValue)
        }
    }

    fun addServer(entry: McpServerEntry) {
        _servers.update { current -> current + entry }
        persistServers()
    }

    fun updateServer(entry: McpServerEntry) {
        _servers.update { current ->
            current.map { if (it.id == entry.id) entry else it }
        }
        persistServers()
    }

    fun removeServer(id: String) {
        _servers.update { current -> current.filter { it.id != id } }
        persistServers()
    }

    fun toggleServer(id: String) {
        _servers.update { current ->
            current.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        }
        persistServers()
    }

    fun addFromCurated(
        curated: CuratedMcpServers.CuratedServer,
        envValues: Map<String, String> = emptyMap(),
        pathArg: String = "",
    ) {
        val args = curated.args.toMutableList()
        if (pathArg.isNotBlank() && curated.transport == McpTransportType.STDIO) {
            // Replace trailing placeholder path if present
            val lastArg = args.lastOrNull()
            if (lastArg != null && (lastArg.startsWith("/") || lastArg == "PATH")) {
                args[args.lastIndex] = pathArg
            } else {
                args.add(pathArg)
            }
        }

        // For HTTP/SSE transport, env vars are not supported server-side — they must
        // be converted to HTTP headers. Common convention: if a single env key is
        // named *_TOKEN or *_KEY, emit it as `Authorization: Bearer <value>`.
        val headers = if (curated.transport == McpTransportType.HTTP ||
            curated.transport == McpTransportType.SSE
        ) {
            envValues.map { (key, value) ->
                if (key.endsWith("_TOKEN") || key.endsWith("_KEY") || key.endsWith("_AUTH_TOKEN")) {
                    "Authorization" to "Bearer $value"
                } else {
                    key to value
                }
            }.toMap()
        } else {
            emptyMap()
        }

        val entry = McpServerEntry(
            name = curated.name,
            enabled = true,
            transport = curated.transport,
            command = curated.command,
            args = args,
            url = curated.url,
            env = if (curated.transport == McpTransportType.STDIO ||
                curated.transport == McpTransportType.LOCALHOST_STDIO
            ) {
                envValues
            } else {
                emptyMap()
            },
            headers = headers,
        )
        addServer(entry)
    }

    private fun persistServers() {
        val jsonString = json.encodeToString(ListSerializer(McpServerEntry.serializer()), _servers.value)
        viewModelScope.launch {
            settingsRepository.setMcpServersJson(jsonString)
        }
    }

    private fun parseServers(jsonString: String): List<McpServerEntry> =
        if (jsonString.isBlank() || jsonString == "[]") {
            emptyList()
        } else {
            try {
                json.decodeFromString(jsonString)
            } catch (_: Exception) {
                emptyList()
            }
        }
}
