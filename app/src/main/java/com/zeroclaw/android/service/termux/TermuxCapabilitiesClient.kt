/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

data class TermuxCommandCapability(
    val name: String,
    val available: Boolean,
    val path: String? = null,
    val version: String? = null,
)

data class TermuxExecutionLimits(
    val approvalRequired: Boolean,
    val timeoutSeconds: Int?,
    val maxTimeoutSeconds: Int?,
    val maxOutputBytes: Int?,
    val executionMode: String? = null,
)

data class TermuxCapabilitiesSnapshot(
    val endpoint: String,
    val bridgeVersion: String?,
    val workspaceRoot: String?,
    val termuxHome: String?,
    val termuxUsr: String?,
    val commands: List<TermuxCommandCapability>,
    val pythonVersion: String?,
    val pythonExecutable: String?,
    val proot: TermuxProotState,
    val limits: TermuxExecutionLimits,
)

sealed interface TermuxCapabilitiesResult {
    data class Success(val snapshot: TermuxCapabilitiesSnapshot) : TermuxCapabilitiesResult

    data class Failure(val reason: String) : TermuxCapabilitiesResult
}

interface TermuxCapabilitiesClient {
    suspend fun fetchCapabilities(): TermuxCapabilitiesResult
}

class HttpTermuxCapabilitiesClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val endpoints: List<TermuxBridgeEndpoint>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TermuxCapabilitiesClient {
    override suspend fun fetchCapabilities(): TermuxCapabilitiesResult =
        withContext(ioDispatcher) {
            if (endpoints.isEmpty()) {
                return@withContext TermuxCapabilitiesResult.Failure(
                    "No Termux bridge capability endpoints are configured.",
                )
            }

            val failures = mutableListOf<String>()
            endpoints.forEach { endpoint ->
                when (val result = fetch(endpoint)) {
                    is TermuxCapabilitiesResult.Success -> return@withContext result
                    is TermuxCapabilitiesResult.Failure -> failures += result.reason
                }
            }
            TermuxCapabilitiesResult.Failure(failures.joinToString(separator = " "))
        }

    private fun fetch(endpoint: TermuxBridgeEndpoint): TermuxCapabilitiesResult {
        val capabilitiesUrl = endpoint.capabilitiesUrl()
        val request =
            Request.Builder()
                .url(capabilitiesUrl)
                .get()
                .also { builder ->
                    endpoint.token?.takeIf { it.isNotBlank() }?.let { token ->
                        val value =
                            if (endpoint.useBearerPrefix) {
                                "Bearer $token"
                            } else {
                                token
                            }
                        builder.header(endpoint.tokenHeaderName, value)
                    }
                }
                .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN ->
                        TermuxCapabilitiesResult.Failure(
                            "Termux bridge rejected capability authentication at $capabilitiesUrl (${response.code}).",
                        )
                    !response.isSuccessful ->
                        TermuxCapabilitiesResult.Failure(
                            "Termux bridge capabilities endpoint at $capabilitiesUrl returned HTTP ${response.code}.",
                        )
                    else -> parseCapabilities(body, capabilitiesUrl)
                }
            }
        } catch (e: IOException) {
            TermuxCapabilitiesResult.Failure(
                "Termux bridge capabilities endpoint $capabilitiesUrl is not reachable: ${e.message ?: e::class.java.simpleName}.",
            )
        } catch (e: IllegalArgumentException) {
            TermuxCapabilitiesResult.Failure(
                "Termux bridge capabilities endpoint is invalid: ${e.message ?: endpoint.baseUrl}.",
            )
        }
    }

    private fun parseCapabilities(
        body: String,
        endpoint: String,
    ): TermuxCapabilitiesResult {
        return try {
            val root = JSONObject(body)
            if (!root.optBoolean("success", true)) {
                return TermuxCapabilitiesResult.Failure("Termux bridge reported capability discovery failure.")
            }
            val bridge = root.optJSONObject("bridge")
            val workspace = root.optJSONObject("workspace")
            val python = root.optJSONObject("python")
            val limits = root.optJSONObject("limits")
            TermuxCapabilitiesResult.Success(
                TermuxCapabilitiesSnapshot(
                    endpoint = endpoint,
                    bridgeVersion = bridge?.optNullableString("version") ?: root.optNullableString("version"),
                    workspaceRoot = workspace?.firstNonBlank("root", "path", "workspace"),
                    termuxHome = workspace?.firstNonBlank("termux_home", "home"),
                    termuxUsr = workspace?.firstNonBlank("termux_usr", "usr"),
                    commands = root.commandCapabilities(),
                    pythonVersion = python?.optNullableString("version"),
                    pythonExecutable = python?.optNullableString("executable"),
                    proot = root.prootState(),
                    limits =
                        TermuxExecutionLimits(
                            approvalRequired = limits?.optBoolean("approval_required", true) ?: true,
                            timeoutSeconds = limits?.optNullableInt("timeout_seconds"),
                            maxTimeoutSeconds = limits?.optNullableInt("max_timeout_seconds"),
                            maxOutputBytes = limits?.optNullableInt("max_output_bytes"),
                            executionMode = limits?.optNullableString("execution_mode"),
                        ),
                ),
            )
        } catch (_: JSONException) {
            TermuxCapabilitiesResult.Failure("Termux bridge capabilities endpoint $endpoint returned invalid JSON.")
        }
    }

    private fun JSONObject.commandCapabilities(): List<TermuxCommandCapability> {
        val commands = optJSONObject("commands") ?: return emptyList()
        return buildList {
            val names = commands.keys()
            while (names.hasNext()) {
                val name = names.next()
                val item = commands.optJSONObject(name) ?: continue
                add(
                    TermuxCommandCapability(
                        name = name,
                        available = item.optBoolean("available", false),
                        path = item.optNullableString("path"),
                        version = item.optNullableString("version"),
                    ),
                )
            }
        }.sortedBy { it.name }
    }

    private fun JSONObject.prootState(): TermuxProotState {
        val proot = optJSONObject("proot")
        val available =
            when {
                proot?.has("available") == true -> proot.optBoolean("available")
                has("proot_available") -> optBoolean("proot_available")
                else -> null
            }
        val activeDistro =
            proot?.firstNonBlank("activeDistro", "active_distro", "active")
                ?: firstNonBlank("proot_active_distro", "active_distro")
        return TermuxProotState(
            available = available,
            activeDistro = activeDistro,
            distros = proot?.optJSONArray("distros").toStringList(),
        )
    }

    private fun TermuxBridgeEndpoint.capabilitiesUrl(): String = "${baseUrl.trimEnd('/')}/capabilities"

    private fun JSONObject.firstNonBlank(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> optNullableString(name) }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) {
            optString(name).takeIf { it.isNotBlank() }
        } else {
            null
        }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) {
            optInt(name)
        } else {
            null
        }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }
}
