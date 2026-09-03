/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

sealed interface TermuxExecutionChunk {
    data class Stdout(val data: String) : TermuxExecutionChunk
    data class Stderr(val data: String) : TermuxExecutionChunk
    data class Result(
        val executionId: String,
        val success: Boolean,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val durationMs: Int?,
    ) : TermuxExecutionChunk
    data class Error(val message: String) : TermuxExecutionChunk
}

/**
 * Streaming execution client for Termux bridge.
 *
 * Falls back to batch execution if the bridge doesn't support streaming.
 */
class TermuxStreamingExecutor(
    private val httpClient: OkHttpClient,
    private val endpoints: List<TermuxBridgeEndpoint>,
) {
    fun executeStreaming(
        command: String,
        arguments: List<String>,
        workingDirectory: String?,
        timeoutSeconds: Int,
    ): Flow<TermuxExecutionChunk> = flow {
        val argv = listOf(command) + arguments
        val payload =
            JSONObject()
                .put("argv", JSONArray(argv))
                .also { root ->
                    workingDirectory?.takeIf { it.isNotBlank() }?.let {
                        root.put("working_directory", it)
                    }
                    root.put("timeout_seconds", timeoutSeconds)
                }

        for (endpoint in endpoints) {
            val url = "${endpoint.baseUrl.trimEnd('/')}/execute_stream"
            val request =
                Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .also { builder ->
                        endpoint.token?.takeIf { it.isNotBlank() }?.let { token ->
                            val value =
                                if (endpoint.useBearerPrefix) "Bearer $token" else token
                            builder.header(endpoint.tokenHeaderName, value)
                        }
                    }
                    .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        return@flow  // bridge doesn't support streaming, caller should fall back
                    }
                    if (!response.isSuccessful) {
                        emit(TermuxExecutionChunk.Error("HTTP ${response.code} from $url"))
                        return@flow
                    }
                    val reader =
                        response.body?.byteStream()?.bufferedReader()
                            ?: return@flow
                    reader.useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            val chunk = parseChunk(line) ?: continue
                            emit(chunk)
                            if (chunk is TermuxExecutionChunk.Result || chunk is TermuxExecutionChunk.Error) {
                                return@flow
                            }
                        }
                    }
                }
                return@flow
            } catch (_: IOException) {
                continue  // try next endpoint
            }
        }
        emit(TermuxExecutionChunk.Error("No Termux bridge streaming endpoints responded."))
    }.flowOn(Dispatchers.IO)

    private fun parseChunk(line: String): TermuxExecutionChunk? =
        runCatching {
            val root = JSONObject(line)
            when (root.optString("chunk_type")) {
                "stdout" -> TermuxExecutionChunk.Stdout(root.optString("data"))
                "stderr" -> TermuxExecutionChunk.Stderr(root.optString("data"))
                "result" ->
                    TermuxExecutionChunk.Result(
                        executionId = root.optString("execution_id"),
                        success = root.optBoolean("success", false),
                        exitCode = root.optInt("exit_code", -1).takeIf { root.has("exit_code") },
                        stdout = root.optString("stdout"),
                        stderr = root.optString("stderr"),
                        durationMs = root.optInt("duration_ms", 0),
                    )
                "error" -> TermuxExecutionChunk.Error(root.optString("error", "Unknown error"))
                else -> null
            }
        }.getOrNull()

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
