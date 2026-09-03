/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

data class TermuxExecutionRequest(
    val argv: List<String>,
    val workingDirectory: String? = null,
    val timeoutSeconds: Int? = null,
    val approval: TermuxExecutionApproval? = null,
)

data class TermuxExecutionApproval(
    val fingerprint: String,
    val risk: TermuxCommandRisk,
)

data class TermuxExecutionSnapshot(
    val success: Boolean,
    val id: String?,
    val status: String?,
    val argv: List<String>,
    val workingDirectory: String?,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMs: Int?,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

sealed interface TermuxExecutionResult {
    data class Success(val snapshot: TermuxExecutionSnapshot) : TermuxExecutionResult

    data class Failure(val reason: String) : TermuxExecutionResult
}

interface TermuxExecutionClient {
    suspend fun execute(request: TermuxExecutionRequest): TermuxExecutionResult
}

class HttpTermuxExecutionClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val endpoints: List<TermuxBridgeEndpoint>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TermuxExecutionClient {
    override suspend fun execute(request: TermuxExecutionRequest): TermuxExecutionResult =
        withContext(ioDispatcher) {
            if (request.argv.isEmpty() || request.argv.any { it.isBlank() }) {
                return@withContext TermuxExecutionResult.Failure(
                    "Termux execution requires a non-empty argv list.",
                )
            }
            if (endpoints.isEmpty()) {
                return@withContext TermuxExecutionResult.Failure(
                    "No Termux bridge execution endpoints are configured.",
                )
            }

            val failures = mutableListOf<String>()
            endpoints.forEach { endpoint ->
                when (val result = execute(endpoint, request)) {
                    is TermuxExecutionResult.Success -> return@withContext result
                    is TermuxExecutionResult.Failure -> failures += result.reason
                }
            }
            TermuxExecutionResult.Failure(failures.joinToString(separator = " "))
        }

    private fun execute(
        endpoint: TermuxBridgeEndpoint,
        executionRequest: TermuxExecutionRequest,
    ): TermuxExecutionResult {
        val executeUrl = endpoint.executeUrl()
        val request =
            Request.Builder()
                .url(executeUrl)
                .post(executionRequest.toJson().toString().toRequestBody(JSON_MEDIA_TYPE))
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
                        TermuxExecutionResult.Failure(
                            "Termux bridge rejected execution authentication at $executeUrl (${response.code}).",
                        )
                    !response.isSuccessful ->
                        parseExecutionFailure(body)
                            ?: TermuxExecutionResult.Failure(
                                "Termux bridge execution endpoint at $executeUrl returned HTTP ${response.code}.",
                            )
                    else ->
                        parseExecution(body, executeUrl)
                }
            }
        } catch (e: IOException) {
            TermuxExecutionResult.Failure(
                "Termux bridge execution endpoint $executeUrl is not reachable: ${e.message ?: e::class.java.simpleName}.",
            )
        } catch (e: IllegalArgumentException) {
            TermuxExecutionResult.Failure(
                "Termux bridge execution endpoint is invalid: ${e.message ?: endpoint.baseUrl}.",
            )
        }
    }

    private fun parseExecution(
        body: String,
        endpoint: String,
    ): TermuxExecutionResult =
        try {
            val root = JSONObject(body)
            TermuxExecutionResult.Success(
                TermuxExecutionSnapshot(
                    success = root.optBoolean("success", false),
                    id = root.optNullableString("id"),
                    status = root.optNullableString("status"),
                    argv = root.optJSONArray("argv").toStringList(),
                    workingDirectory = root.optNullableString("working_directory"),
                    exitCode = root.optNullableInt("exit_code"),
                    stdout = root.optString("stdout"),
                    stderr = root.optString("stderr"),
                    durationMs = root.optNullableInt("duration_ms"),
                    stdoutTruncated = root.optBoolean("stdout_truncated", false),
                    stderrTruncated = root.optBoolean("stderr_truncated", false),
                ),
            )
        } catch (_: JSONException) {
            TermuxExecutionResult.Failure("Termux bridge execution endpoint $endpoint returned invalid JSON.")
        }

    private fun parseExecutionFailure(body: String): TermuxExecutionResult.Failure? =
        runCatching {
            val root = JSONObject(body)
            val error = root.optJSONObject("error")
            val message =
                error?.optNullableString("message")
                    ?: root.optNullableString("message")
                    ?: root.optNullableString("reason")
                    ?: return null
            TermuxExecutionResult.Failure(message)
        }.getOrNull()

    private fun TermuxExecutionRequest.toJson(): JSONObject =
        JSONObject()
            .put("argv", JSONArray(argv))
            .also { root ->
                workingDirectory?.takeIf { it.isNotBlank() }?.let { root.put("working_directory", it) }
                timeoutSeconds?.let { root.put("timeout_seconds", it) }
                approval?.let { approval ->
                    root.put(
                        "approval",
                        JSONObject()
                            .put("approved", true)
                            .put("fingerprint", approval.fingerprint)
                            .put("risk", approval.risk.name),
                    )
                }
            }

    private fun TermuxBridgeEndpoint.executeUrl(): String = "${baseUrl.trimEnd('/')}/execute"

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

    private fun JSONArray?.toStringList(): List<String> {
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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
