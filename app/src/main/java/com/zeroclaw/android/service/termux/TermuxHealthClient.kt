/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

interface TermuxHealthClient {
    suspend fun checkHealth(): TermuxHealthSnapshot
}

object InactiveTermuxHealthClient : TermuxHealthClient {
    override suspend fun checkHealth(): TermuxHealthSnapshot =
        TermuxHealthSnapshot(
            status = TermuxHealthStatus.UNAVAILABLE,
            reason = "Termux command execution is not implemented in this Android build yet.",
        )
}

data class TermuxBridgeEndpoint(
    val baseUrl: String,
    val token: String? = null,
    val tokenHeaderName: String = "Authorization",
    val useBearerPrefix: Boolean = true,
) {
    init {
        require(isLocalTermuxBridgeBaseUrl(baseUrl)) {
            "Termux bridge baseUrl must be a local HTTP URL."
        }
        require(isAllowedTermuxBridgeTokenHeader(tokenHeaderName)) {
            "Termux bridge token header must use the approved bridge auth header."
        }
    }
}

private fun isLocalTermuxBridgeBaseUrl(rawBaseUrl: String): Boolean {
    val normalized = rawBaseUrl.trim().trimEnd('/')
    if (normalized.isEmpty()) return false
    val url = normalized.toHttpUrlOrNull() ?: return false
    return url.scheme == "http" &&
        url.encodedUsername.isEmpty() &&
        url.encodedPassword.isEmpty() &&
        url.query == null &&
        url.fragment == null &&
        url.encodedPath == "/" &&
        url.host in LOCAL_TERMUX_BRIDGE_HOSTS
}

private fun isAllowedTermuxBridgeTokenHeader(headerName: String): Boolean =
    headerName.equals("Authorization", ignoreCase = true) ||
        headerName.equals(TermuxRuntimeContract.BRIDGE_TOKEN_HEADER, ignoreCase = true)

private val LOCAL_TERMUX_BRIDGE_HOSTS = setOf("127.0.0.1", "localhost", "::1")

class HttpTermuxHealthClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val endpoints: List<TermuxBridgeEndpoint> = DEFAULT_ENDPOINTS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TermuxHealthClient {
    override suspend fun checkHealth(): TermuxHealthSnapshot =
        withContext(ioDispatcher) {
            if (endpoints.isEmpty()) {
                return@withContext TermuxHealthSnapshot(
                    status = TermuxHealthStatus.UNAVAILABLE,
                    reason = "No Termux bridge health endpoints are configured.",
                )
            }

            val failures = mutableListOf<String>()
            endpoints.forEach { endpoint ->
                when (val result = probe(endpoint)) {
                    is HealthProbeResult.Success -> return@withContext result.snapshot
                    is HealthProbeResult.Failure -> failures += result.reason
                }
            }

            TermuxHealthSnapshot(
                status = TermuxHealthStatus.UNAVAILABLE,
                reason = failures.joinToString(separator = " "),
            )
        }

    private fun probe(endpoint: TermuxBridgeEndpoint): HealthProbeResult {
        val healthUrl = endpoint.healthUrl()
        val request =
            Request.Builder()
                .url(healthUrl)
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
                        HealthProbeResult.Failure(
                            "Termux bridge rejected health probe authentication at $healthUrl (${response.code}).",
                        )
                    !response.isSuccessful ->
                        HealthProbeResult.Failure(
                            "Termux bridge health probe at $healthUrl returned HTTP ${response.code}.",
                        )
                    else ->
                        parseHealth(body, healthUrl)
                }
            }
        } catch (e: IOException) {
            HealthProbeResult.Failure(
                "Termux bridge health endpoint $healthUrl is not reachable: ${e.message ?: e::class.java.simpleName}.",
            )
        } catch (e: IllegalArgumentException) {
            HealthProbeResult.Failure(
                "Termux bridge health endpoint is invalid: ${e.message ?: endpoint.baseUrl}.",
            )
        }
    }

    private fun parseHealth(
        body: String,
        endpoint: String,
    ): HealthProbeResult =
        try {
            val root = JSONObject(body)
            val ready = root.optBoolean("ready", false) || root.optString("status").equals("ready", ignoreCase = true)
            val reason = root.firstNonBlank("reason", "message", "status")
            val details =
                TermuxBridgeHealthDetails(
                    endpoint = endpoint,
                    version = root.optNullableString("version"),
                    workspace = root.workspaceText(),
                    proot = root.prootState(),
                )

            HealthProbeResult.Success(
                TermuxHealthSnapshot(
                    status =
                        if (ready) {
                            TermuxHealthStatus.READY
                        } else {
                            TermuxHealthStatus.UNAVAILABLE
                        },
                    reason =
                        if (ready) {
                            reason ?: "Termux bridge is ready."
                        } else {
                            reason ?: "Termux bridge health endpoint is not ready."
                        },
                    details = details,
                ),
            )
        } catch (_: JSONException) {
            HealthProbeResult.Failure("Termux bridge health endpoint $endpoint returned invalid JSON.")
        }

    private fun TermuxBridgeEndpoint.healthUrl(): String = "${baseUrl.trimEnd('/')}/health"

    private fun JSONObject.workspaceText(): String? {
        optNullableString("workspace")?.let { return it }
        val workspace = optJSONObject("workspace") ?: return null
        return workspace.firstNonBlank("path", "root", "name", "id")
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
        val prootDistros = proot?.optJSONArray("distros")
        val rootDistros = optJSONArray("distros")
        val distros =
            when {
                prootDistros != null -> prootDistros.toStringList()
                rootDistros != null -> rootDistros.toStringList()
                else -> emptyList()
            }
        return TermuxProotState(
            available = available,
            activeDistro = activeDistro,
            distros = distros,
        )
    }

    private fun JSONObject.firstNonBlank(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> optNullableString(name) }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) {
            optString(name).takeIf { it.isNotBlank() }
        } else {
            null
        }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private sealed interface HealthProbeResult {
        data class Success(val snapshot: TermuxHealthSnapshot) : HealthProbeResult

        data class Failure(val reason: String) : HealthProbeResult
    }

    private companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403

        private val DEFAULT_ENDPOINTS =
            listOf(
                TermuxBridgeEndpoint(TermuxRuntimeContract.DEFAULT_BRIDGE_BASE_URL),
                TermuxBridgeEndpoint(TermuxRuntimeContract.FALLBACK_BRIDGE_BASE_URL),
            )
    }
}

interface TermuxRuntimeStatusProvider {
    suspend fun currentStatus(): TermuxRuntimeStatus
}

class DefaultTermuxRuntimeStatusProvider(
    private val probe: TermuxRuntimeProbe,
    private val healthClient: TermuxHealthClient = InactiveTermuxHealthClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TermuxRuntimeStatusProvider {
    override suspend fun currentStatus(): TermuxRuntimeStatus =
        withContext(ioDispatcher) {
            val snapshot = probe.snapshot()
            val health =
                if (
                    snapshot.packageState.availability == TermuxPackageAvailability.INSTALLED &&
                    snapshot.permissionState.availability == TermuxPermissionAvailability.GRANTED
                ) {
                    healthClient.checkHealth()
                } else {
                    TermuxHealthSnapshot(
                        status = TermuxHealthStatus.UNAVAILABLE,
                        reason = "Termux package and permission checks must pass before health probing.",
                    )
                }
            TermuxRuntimeStatus(
                packageState = snapshot.packageState,
                permissionState = snapshot.permissionState,
                bootstrapState = snapshot.bootstrapState,
                health = health,
            )
        }
}
