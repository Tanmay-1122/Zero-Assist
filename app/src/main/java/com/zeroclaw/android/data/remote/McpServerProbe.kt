/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import com.zeroclaw.android.model.McpTransportType
import com.zeroclaw.android.network.CleartextHttpPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Result classification of a single MCP server probe attempt.
 */
sealed interface McpProbeOutcome {
    /** The server answered with a valid MCP protocol exchange. */
    data class Reachable(val detail: String) : McpProbeOutcome

    /** The server requires credentials (HTTP 401/403). */
    data class AuthRequired(val status: Int) : McpProbeOutcome

    /** The server responded but does not speak MCP at this URL. */
    data class NotMcp(val reason: String) : McpProbeOutcome

    /** The server could not be reached (timeout, DNS, connect failure). */
    data class Unreachable(val reason: String) : McpProbeOutcome
}

/**
 * Probes an MCP server URL before it is added to the configuration.
 *
 * Unlike the older plain-GET check (which false-fails Streamable HTTP
 * servers that only accept POST JSON-RPC), this probe speaks the actual
 * MCP protocol: it POSTs a JSON-RPC `initialize` request with the proper
 * `Accept: application/json, text/event-stream` and `MCP-Protocol-Version`
 * headers, and tolerates servers that reply 405 to GET by falling back to
 * an SSE-style GET probe (legacy SSE transport).
 *
 * Use [probe] for live network validation and [messageFor] for
 * unit-testable classification without network access.
 */
object McpServerProbe {
    /** MCP protocol version announced during the `initialize` handshake. */
    private const val PROTOCOL_VERSION = "2024-11-05"

    /** Connect/read timeout for probe requests (remote servers are slow on first connect). */
    private const val PROBE_TIMEOUT_SECONDS = 15L

    /** MIME types accepted for Streamable HTTP responses. */
    private const val MIME_EVENT_STREAM = "text/event-stream"
    private const val MIME_JSON = "application/json"

    private val json = Json { ignoreUnknownKeys = true }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * Probes the MCP server at [url] using the given [transport] and optional
     * [headers] (e.g. Authorization). Returns [Result.success] with a
     * human-readable message when the server responds to the MCP protocol,
     * or [Result.failure] with a user-friendly message otherwise.
     */
    @Suppress("InjectDispatcher")
    suspend fun probe(
        url: String,
        transport: McpTransportType,
        headers: Map<String, String>,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) {
                return@withContext messageFor(McpProbeOutcome.NotMcp("URL is blank"))
            }
            try {
                CleartextHttpPolicy.requireAllowed(url, "MCP probe")
                val outcome =
                    when (transport) {
                        McpTransportType.HTTP -> probeStreamableHttp(url, headers)
                        McpTransportType.SSE -> probeSse(url, headers)
                        else ->
                            McpProbeOutcome.NotMcp(
                                "URL validation is only available for HTTP/SSE transport",
                            )
                    }
                messageFor(outcome)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                messageFor(McpProbeOutcome.Unreachable(error.message ?: "Unreachable"))
            }
        }

    /**
     * Converts a [McpProbeOutcome] into the [Result] surfaced to the UI.
     *
     * Exposed as `internal` for unit testing without network access.
     */
    internal fun messageFor(outcome: McpProbeOutcome): Result<String> =
        when (outcome) {
            is McpProbeOutcome.Reachable -> Result.success(outcome.detail)
            is McpProbeOutcome.AuthRequired ->
                Result.failure(
                    IOException(
                        "HTTP ${outcome.status} Auth Required - add an Authorization header",
                    ),
                )
            is McpProbeOutcome.NotMcp -> Result.failure(IOException(outcome.reason))
            is McpProbeOutcome.Unreachable ->
                Result.failure(IOException("Could not reach server - ${outcome.reason}"))
        }

    private fun probeStreamableHttp(
        url: String,
        headers: Map<String, String>,
    ): McpProbeOutcome {
        val start = System.currentTimeMillis()
        val request =
            buildRequest(url, headers)
                .newBuilder()
                .post(buildInitializeBody().toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().use { response ->
            val status = response.code
            val contentType = response.header("Content-Type").orEmpty()
            val elapsed = System.currentTimeMillis() - start
            return when {
                status in 200..299 -> classifySuccessResponse(response, contentType, elapsed)
                status == 401 || status == 403 -> McpProbeOutcome.AuthRequired(status)
                status == 400 || status == 405 || status == 406 ->
                    probeGetFallback(url, headers, status)
                else ->
                    McpProbeOutcome.NotMcp(
                        "Server responded HTTP $status but does not accept MCP requests at this URL",
                    )
            }
        }
    }

    private fun classifySuccessResponse(
        response: Response,
        contentType: String,
        elapsed: Long,
    ): McpProbeOutcome {
        val bodyText = response.body?.string().orEmpty()
        return when {
            contentType.contains(MIME_EVENT_STREAM, ignoreCase = true) ->
                parseSseResponse(bodyText, response.code, elapsed)
            contentType.contains(MIME_JSON, ignoreCase = true) ->
                parseJsonResponse(bodyText, response.code, elapsed)
            bodyText.isBlank() || response.code == 202 ->
                McpProbeOutcome.Reachable("Server reachable (HTTP ${response.code} OK) - ${elapsed}ms")
            else ->
                McpProbeOutcome.NotMcp(
                    "Server responded HTTP ${response.code} but not with MCP content - ${elapsed}ms",
                )
        }
    }

    private fun parseJsonResponse(bodyText: String, status: Int, elapsed: Long): McpProbeOutcome {
        if (bodyText.isBlank()) {
            return McpProbeOutcome.Reachable("Server reachable (HTTP $status OK) - ${elapsed}ms")
        }
        return runCatching {
            val root = json.parseToJsonElement(bodyText).jsonObject
            when {
                root.containsKey("result") -> {
                    val serverInfo = root["result"]?.jsonObject?.get("serverInfo")
                    val name = serverInfo?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val version = serverInfo?.jsonObject?.get("version")?.jsonPrimitive?.contentOrNull
                    val identity = listOfNotNull(name, version).joinToString(" ")
                    McpProbeOutcome.Reachable(
                        if (identity.isBlank()) {
                            "Server responding (HTTP $status MCP initialize) - ${elapsed}ms"
                        } else {
                            "Server responding (HTTP $status) - $identity - ${elapsed}ms"
                        },
                    )
                }
                root.containsKey("error") -> {
                    val code = root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                    McpProbeOutcome.Reachable(
                        "Server responding (HTTP $status, MCP error ${code ?: "response"}) - ${elapsed}ms",
                    )
                }
                else ->
                    McpProbeOutcome.Reachable(
                        "Server responding (HTTP $status OK) - ${elapsed}ms",
                    )
            }
        }.getOrElse {
            McpProbeOutcome.Reachable("Server responding (HTTP $status OK) - ${elapsed}ms")
        }
    }

    private fun parseSseResponse(bodyText: String, status: Int, elapsed: Long): McpProbeOutcome {
        for (line in bodyText.lineSequence()) {
            val data = line.trim().removePrefix("data:")
            if (data.isBlank() || !data.startsWith("{")) continue
            val parsed =
                runCatching { json.parseToJsonElement(data).jsonObject }
                    .getOrNull() ?: continue
            if (parsed.containsKey("result") || parsed.containsKey("error")) {
                return McpProbeOutcome.Reachable(
                    "Server responding (HTTP $status MCP stream) - ${elapsed}ms",
                )
            }
        }
        return McpProbeOutcome.Reachable(
            "Server responding (HTTP $status event stream) - ${elapsed}ms",
        )
    }

    private fun probeGetFallback(url: String, headers: Map<String, String>, postStatus: Int): McpProbeOutcome {
        val request =
            buildRequest(url, headers)
                .newBuilder()
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            val status = response.code
            val contentType = response.header("Content-Type").orEmpty()
            return when {
                status in 200..299 && contentType.contains(MIME_EVENT_STREAM, ignoreCase = true) ->
                    McpProbeOutcome.Reachable(
                        "Valid SSE/Streamable endpoint (HTTP $status) - POST returned $postStatus, GET streams events",
                    )
                status in 200..299 ->
                    McpProbeOutcome.Reachable(
                        "Server responding (HTTP $status GET) - POST returns HTTP $postStatus",
                    )
                status == 401 || status == 403 -> McpProbeOutcome.AuthRequired(status)
                else ->
                    McpProbeOutcome.NotMcp(
                        "Server rejects MCP requests (HTTP $postStatus on POST, HTTP $status on GET)",
                    )
            }
        }
    }

    private fun probeSse(url: String, headers: Map<String, String>): McpProbeOutcome {
        val request = buildRequest(url, headers).newBuilder().get().build()
        client.newCall(request).execute().use { response ->
            val status = response.code
            val contentType = response.header("Content-Type").orEmpty()
            return when {
                status in 200..299 && contentType.contains(MIME_EVENT_STREAM, ignoreCase = true) ->
                    McpProbeOutcome.Reachable("Valid SSE endpoint (HTTP $status)")
                status == 401 || status == 403 -> McpProbeOutcome.AuthRequired(status)
                status in 200..299 ->
                    McpProbeOutcome.NotMcp(
                        "Server responded HTTP $status but not as an SSE endpoint",
                    )
                else ->
                    McpProbeOutcome.NotMcp(
                        "Server responded HTTP $status; expected an SSE event stream",
                    )
            }
        }
    }

    private fun buildInitializeBody(): String =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
            """"protocolVersion":"$PROTOCOL_VERSION","capabilities":{},""" +
            """"clientInfo":{"name":"zeroclaw-android","version":"1.0"}}}"""

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
    ): Request {
        val builder =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "ZeroClaw-MCP-Validator/1.0")
                .header("Cache-Control", "no-cache")
        headers.forEach { (key, value) ->
            if (key.isNotBlank()) builder.header(key.trim(), value.trim())
        }
        // Protocol-level headers go last so user headers cannot override them.
        return builder
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .header("Accept", "application/json, text/event-stream")
            .build()
    }
}
