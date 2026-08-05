/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import android.util.Log
import com.zeroclaw.android.BuildConfig
import com.zeroclaw.android.model.McpTransportType
import com.zeroclaw.android.network.CleartextHttpPolicy
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Result classification of a single MCP server probe attempt.
 *
 * The classification is deliberately fine-grained: a failed probe can mean
 * many different things (credentials missing, Host header rejected, the URL
 * serving a different protocol, an MCP version mismatch, a session problem,
 * a busy server, ...). Each outcome maps to an actionable user message in
 * [McpServerProbe.messageFor].
 */
sealed interface McpProbeOutcome {
    /** The server answered with a valid MCP protocol exchange. */
    data class Reachable(val detail: String) : McpProbeOutcome

    /** The server demands credentials (HTTP 401, or 403 with an auth challenge/hint). */
    data class AuthenticationRequired(
        val status: Int,
        val wwwAuthenticate: String?,
        val detail: String,
    ) : McpProbeOutcome

    /**
     * The server rejected the HTTP Host header (e.g. "Invalid Host header").
     *
     * This is what happens when an MCP server that is bound to a specific
     * hostname/port is reached through an address it does not recognize
     * (a LAN IP, localhost vs 127.0.0.1, a forwarded port, ...). It is NOT
     * an authentication problem.
     */
    data class InvalidHostHeader(val detail: String) : McpProbeOutcome

    /** The server is not an MCP endpoint, or the transport cannot be probed. */
    data class UnsupportedTransport(val reason: String) : McpProbeOutcome

    /** The server rejected the MCP protocol version we announced. */
    data class InvalidProtocolVersion(val detail: String) : McpProbeOutcome

    /** The server rejected the session (missing, unknown, or expired Mcp-Session-Id). */
    data class InvalidSession(val detail: String) : McpProbeOutcome

    /** The server is up but currently unable to serve (429, 5xx). */
    data class ServerUnavailable(val status: Int, val detail: String) : McpProbeOutcome

    /** The request timed out. */
    data class Timeout(val detail: String) : McpProbeOutcome

    /** The server rejected the JSON-RPC payload (parse error, invalid request). */
    data class InvalidJsonRpc(val detail: String) : McpProbeOutcome

    /** The server answered, but MCP is not exposed at this URL (405/406/...). */
    data class UnsupportedEndpoint(val detail: String) : McpProbeOutcome

    /** No MCP server exists at this URL (404). */
    data class McpNotFound(val detail: String) : McpProbeOutcome

    /** The URL cannot be probed (blank, or a non-http(s) scheme). */
    data class UnsupportedScheme(val detail: String) : McpProbeOutcome

    /** The server could not be reached (DNS, connect, TLS, network failure). */
    data class NetworkFailure(val reason: String) : McpProbeOutcome

    /** An HTTP status/body we could not interpret more precisely. */
    data class UnknownFailure(val status: Int, val detail: String) : McpProbeOutcome
}

/**
 * A captured HTTP exchange, used for both classification and diagnostics.
 */
internal data class HttpExchange(
    val status: Int,
    val headers: Map<String, String>,
    val bodyText: String,
    val contentType: String,
    val elapsedMs: Long,
)

/**
 * One record of probe diagnostics, retained (debug builds) and loggable.
 */
data class McpDiagnosticsEntry(
    val scope: String,
    val url: String,
    val method: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val status: Int?,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val elapsedMs: Long,
    val sessionId: String?,
    val outcome: String,
) {
    /** One-line summary suitable for logcat. */
    fun summary(): String =
        buildString {
            append("[$scope] $method $url -> ")
            append(status?.toString() ?: "no-response")
            append(" (${elapsedMs}ms)")
            if (sessionId != null) append(" session=$sessionId")
            append(" | outcome=$outcome")
            append(" | req-hdrs=${requestHeaders.map { (k, v) -> "$k=$v" }}")
            append(" | resp-hdrs=${responseHeaders.map { (k, v) -> "$k=$v" }}")
            val body = responseBody?.take(400)
            if (!body.isNullOrBlank()) append(" | body=$body")
        }
}

/**
 * Sink for probe diagnostics.
 *
 * Records are always delivered to the registered [sink] (used by tests and,
 * later, by an in-app diagnostics viewer) and additionally logged to logcat
 * in debug builds under the `McpDiagnostics` tag.
 */
object McpDiagnostics {
    var sink: ((McpDiagnosticsEntry) -> Unit)? = null

    fun record(entry: McpDiagnosticsEntry) {
        sink?.invoke(entry)
        if (BuildConfig.DEBUG) {
            Log.d("McpDiagnostics", entry.summary())
        }
    }
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
 * Failures are classified into [McpProbeOutcome] subtypes by status code,
 * headers (notably `WWW-Authenticate`) and the response body, so that the
 * UI can tell "add credentials" apart from "the server rejected the Host
 * header" (a common situation when reaching a local MCP server through a
 * gateway, adb reverse, or port forward) or "wrong URL".
 *
 * Use [probe] for live network validation, [classifyFailure] and
 * [messageFor] for unit-testable classification without network access.
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
     * or [Result.failure] with an actionable message otherwise.
     */
    @Suppress("InjectDispatcher")
    suspend fun probe(
        url: String,
        transport: McpTransportType,
        headers: Map<String, String>,
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isBlank()) {
                messageFor(McpProbeOutcome.UnsupportedScheme("URL is blank"))
            } else if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
                messageFor(
                    McpProbeOutcome.UnsupportedScheme(
                        "Only http:// and https:// URLs can be validated",
                    ),
                )
            } else {
                try {
                    CleartextHttpPolicy.requireAllowed(trimmedUrl, "MCP probe")
                    val outcome =
                        when (transport) {
                            McpTransportType.HTTP -> probeStreamableHttp(trimmedUrl, headers)
                            McpTransportType.SSE -> probeSse(trimmedUrl, headers)
                            else ->
                                McpProbeOutcome.UnsupportedTransport(
                                    "URL validation is only available for HTTP/SSE transport",
                                )
                        }
                    messageFor(outcome)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    messageFor(classifyIoError(error))
                }
            }
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
            is McpProbeOutcome.AuthenticationRequired ->
                Result.failure(
                    IOException(
                        buildString {
                            append("HTTP ${outcome.status} requires authentication")
                            outcome.wwwAuthenticate
                                ?.takeIf { it.isNotBlank() }
                                ?.let { append(" (challenge: $it)") }
                            append(". Add an Authorization header.")
                        },
                    ),
                )
            is McpProbeOutcome.InvalidHostHeader ->
                Result.failure(
                    IOException(
                        "The server rejected the Host header (${outcome.detail}). " +
                            "This usually means the MCP server only accepts its own " +
                            "hostname/port (e.g. 127.0.0.1:27182). If you connect through " +
                            "a gateway, adb reverse, or port forward, make sure the Host " +
                            "header is rewritten to the server's expected value.",
                    ),
                )
            is McpProbeOutcome.UnsupportedTransport ->
                Result.failure(IOException(outcome.reason))
            is McpProbeOutcome.InvalidProtocolVersion ->
                Result.failure(
                    IOException("The server rejected the MCP protocol version - ${outcome.detail}"),
                )
            is McpProbeOutcome.InvalidSession ->
                Result.failure(IOException("The server rejected the session - ${outcome.detail}"))
            is McpProbeOutcome.ServerUnavailable ->
                Result.failure(
                    IOException(
                        "Server is up but unavailable (HTTP ${outcome.status}) - ${outcome.detail}. Try again later.",
                    ),
                )
            is McpProbeOutcome.Timeout ->
                Result.failure(
                    IOException(
                        "Connection timed out (${outcome.detail}). The server may be offline or slow.",
                    ),
                )
            is McpProbeOutcome.InvalidJsonRpc ->
                Result.failure(
                    IOException("The server rejected the MCP request (HTTP 400) - ${outcome.detail}"),
                )
            is McpProbeOutcome.UnsupportedEndpoint ->
                Result.failure(
                    IOException(
                        "${outcome.detail} MCP may be exposed at a different path (often /mcp).",
                    ),
                )
            is McpProbeOutcome.McpNotFound ->
                Result.failure(
                    IOException("No MCP server at this URL (HTTP 404) - ${outcome.detail}"),
                )
            is McpProbeOutcome.UnsupportedScheme ->
                Result.failure(IOException(outcome.detail))
            is McpProbeOutcome.NetworkFailure ->
                Result.failure(IOException("Could not reach server - ${outcome.reason}"))
            is McpProbeOutcome.UnknownFailure ->
                Result.failure(
                    IOException("Unexpected HTTP ${outcome.status} response - ${outcome.detail}"),
                )
        }

    /**
     * Classifies a non-2xx HTTP exchange into a precise [McpProbeOutcome].
     *
     * Pure function, unit-testable without network access. Uses the status
     * code, the `WWW-Authenticate` header, and the error body (JSON-RPC
     * `error.message`, plain JSON `error`, or plain text) to disambiguate
     * authentication from Host-header rejection from session problems etc.
     */
    internal fun classifyFailure(
        status: Int,
        headers: Map<String, String>,
        bodyText: String,
    ): McpProbeOutcome {
        val wwwAuthenticate =
            headers.entries.firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }?.value
        val errorText = extractErrorText(bodyText)
        val lowered = errorText.lowercase()
        return when {
            status == 401 ->
                McpProbeOutcome.AuthenticationRequired(status, wwwAuthenticate, "HTTP 401 requires credentials")
            status == 403 && lowered.contains("host") ->
                McpProbeOutcome.InvalidHostHeader(errorText.ifBlank { "HTTP 403" })
            status == 403 && (wwwAuthenticate != null || hintsAtAuth(lowered)) ->
                McpProbeOutcome.AuthenticationRequired(status, wwwAuthenticate, errorText.ifBlank { "HTTP 403" })
            status == 400 && lowered.contains("session") ->
                McpProbeOutcome.InvalidSession(errorText.ifBlank { "HTTP 400" })
            status == 400 && (lowered.contains("protocol") || lowered.contains("version")) ->
                McpProbeOutcome.InvalidProtocolVersion(errorText.ifBlank { "HTTP 400" })
            status == 400 -> McpProbeOutcome.InvalidJsonRpc(errorText.ifBlank { "HTTP 400" })
            status == 404 && lowered.contains("session") ->
                McpProbeOutcome.InvalidSession("session not found (HTTP 404)")
            status == 404 -> McpProbeOutcome.McpNotFound(errorText.ifBlank { "HTTP 404" })
            status == 405 || status == 406 ->
                McpProbeOutcome.UnsupportedEndpoint(
                    "Server responded HTTP $status to this request.",
                )
            status == 408 -> McpProbeOutcome.Timeout("HTTP 408 Request Timeout")
            status == 429 || status >= 500 ->
                McpProbeOutcome.ServerUnavailable(status, errorText.ifBlank { "HTTP $status" })
            else -> McpProbeOutcome.UnknownFailure(status, errorText.ifBlank { "HTTP $status" })
        }
    }

    private fun classifyIoError(error: Exception): McpProbeOutcome =
        when (error) {
            is SocketTimeoutException, is InterruptedIOException ->
                McpProbeOutcome.Timeout(error.message ?: "request timed out")
            is UnknownHostException -> McpProbeOutcome.NetworkFailure(error.message ?: "unknown host")
            is ConnectException ->
                McpProbeOutcome.NetworkFailure(error.message ?: "connection refused")
            is IOException ->
                if (error.message?.lowercase()?.contains("timeout") == true) {
                    McpProbeOutcome.Timeout(error.message ?: "request timed out")
                } else {
                    McpProbeOutcome.NetworkFailure(error.message ?: "network error")
                }
            else -> McpProbeOutcome.NetworkFailure(error.message ?: "unexpected error")
        }

    private fun hintsAtAuth(loweredError: String): Boolean =
        listOf(
            "authorization",
            "unauthorized",
            "access denied",
            "forbidden",
            "permission",
            "credentials",
            "authenticate",
            "auth token",
        ).any { loweredError.contains(it) }

    /**
     * Extracts a human-readable error string from a response body, supporting
     * JSON-RPC errors (`{"jsonrpc":...,"error":{"message":...}}`), plain JSON
     * (`{"error": "..."}` / `{"message": "..."}`) and plain text bodies.
     */
    internal fun extractErrorText(bodyText: String): String {
        if (bodyText.isBlank()) return ""
        val cleaned = bodyText.trim().removePrefix("\uFEFF")
        val parsed =
            runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
        if (parsed != null) {
            val error = parsed["error"]
            if (error is JsonPrimitive) {
                error.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            }
            if (error is JsonObject) {
                error["message"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            parsed["message"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            if (parsed.containsKey("result")) return ""
            if (parsed.containsKey("error")) return cleaned.take(200)
            return cleaned.take(200)
        }
        val firstLine = cleaned.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
        return firstLine?.take(200) ?: ""
    }

    private fun probeStreamableHttp(
        url: String,
        headers: Map<String, String>,
    ): McpProbeOutcome {
        val start = System.currentTimeMillis()
        val method = "POST"
        val request =
            buildRequest(url, headers)
                .newBuilder()
                .post(buildInitializeBody().toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().use { response ->
            val exchange =
                HttpExchange(
                    status = response.code,
                    headers = response.headers.toMap(),
                    bodyText = response.body?.string().orEmpty(),
                    contentType = response.header("Content-Type").orEmpty(),
                    elapsedMs = System.currentTimeMillis() - start,
                )
            val outcome =
                when {
                    exchange.status in 200..299 ->
                        classifySuccessResponse(exchange)
                    exchange.status == 400 ||
                        exchange.status == 405 ||
                        exchange.status == 406 ->
                        probeGetFallback(url, headers, exchange)
                    else -> classifyFailure(exchange.status, exchange.headers, exchange.bodyText)
                }
            recordDiagnostics("probe-post", url, method, headers, request, exchange, outcome)
            return outcome
        }
    }

    private fun classifySuccessResponse(exchange: HttpExchange): McpProbeOutcome {
        val bodyText = exchange.bodyText
        return when {
            exchange.contentType.contains(MIME_EVENT_STREAM, ignoreCase = true) ->
                parseSseResponse(bodyText, exchange.status, exchange.elapsedMs)
            exchange.contentType.contains(MIME_JSON, ignoreCase = true) ->
                parseJsonResponse(bodyText, exchange.status, exchange.elapsedMs)
            bodyText.isBlank() || exchange.status == 202 ->
                McpProbeOutcome.Reachable(
                    "Server reachable (HTTP ${exchange.status} OK) - ${exchange.elapsedMs}ms",
                )
            else ->
                McpProbeOutcome.UnsupportedTransport(
                    "Server responded HTTP ${exchange.status} but not with MCP content - ${exchange.elapsedMs}ms",
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

    private fun probeGetFallback(
        url: String,
        headers: Map<String, String>,
        post: HttpExchange,
    ): McpProbeOutcome {
        val start = System.currentTimeMillis()
        val request = buildRequest(url, headers).newBuilder().get().build()
        client.newCall(request).execute().use { response ->
            val exchange =
                HttpExchange(
                    status = response.code,
                    headers = response.headers.toMap(),
                    bodyText = response.body?.string().orEmpty(),
                    contentType = response.header("Content-Type").orEmpty(),
                    elapsedMs = System.currentTimeMillis() - start,
                )
            val outcome =
                when {
                    exchange.status in 200..299 &&
                        exchange.contentType.contains(MIME_EVENT_STREAM, ignoreCase = true) ->
                        McpProbeOutcome.Reachable(
                            "Valid SSE/Streamable endpoint (HTTP ${exchange.status}) - POST returned ${post.status}, GET streams events",
                        )
                    exchange.status in 200..299 ->
                        McpProbeOutcome.Reachable(
                            "Server responding (HTTP ${exchange.status} GET) - POST returns HTTP ${post.status}",
                        )
                    else ->
                        classifyFailure(exchange.status, exchange.headers, exchange.bodyText)
                }
            recordDiagnostics("probe-get-fallback", url, "GET", headers, request, exchange, outcome)
            return outcome
        }
    }

    private fun probeSse(url: String, headers: Map<String, String>): McpProbeOutcome {
        val start = System.currentTimeMillis()
        val request = buildRequest(url, headers).newBuilder().get().build()
        client.newCall(request).execute().use { response ->
            val exchange =
                HttpExchange(
                    status = response.code,
                    headers = response.headers.toMap(),
                    bodyText = response.body?.string().orEmpty(),
                    contentType = response.header("Content-Type").orEmpty(),
                    elapsedMs = System.currentTimeMillis() - start,
                )
            val outcome =
                when {
                    exchange.status in 200..299 &&
                        exchange.contentType.contains(MIME_EVENT_STREAM, ignoreCase = true) ->
                        McpProbeOutcome.Reachable("Valid SSE endpoint (HTTP ${exchange.status})")
                    exchange.status in 200..299 ->
                        McpProbeOutcome.UnsupportedTransport(
                            "Server responded HTTP ${exchange.status} but not as an SSE endpoint",
                        )
                    else ->
                        classifyFailure(exchange.status, exchange.headers, exchange.bodyText)
                }
            recordDiagnostics("probe-sse", url, "GET", headers, request, exchange, outcome)
            return outcome
        }
    }

    private fun recordDiagnostics(
        scope: String,
        url: String,
        method: String,
        userHeaders: Map<String, String>,
        request: Request,
        exchange: HttpExchange?,
        outcome: McpProbeOutcome,
    ) {
        val sessionId = exchange?.headers?.entries?.firstOrNull {
            it.key.equals("Mcp-Session-Id", ignoreCase = true)
        }?.value
        McpDiagnostics.record(
            McpDiagnosticsEntry(
                scope = scope,
                url = url,
                method = method,
                requestHeaders = maskedHeaders(userHeaders),
                requestBody = request.body?.contentLength()?.let { "($it bytes)" },
                status = exchange?.status,
                responseHeaders = exchange?.headers ?: emptyMap(),
                responseBody = exchange?.bodyText,
                elapsedMs = exchange?.elapsedMs ?: -1L,
                sessionId = sessionId,
                outcome = outcome::class.simpleName ?: "Unknown",
            ),
        )
    }

    /** Redacts credential-bearing headers before they reach diagnostics. */
    private fun maskedHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, _) ->
            if (key.equals("Authorization", ignoreCase = true) ||
                key.equals("Proxy-Authorization", ignoreCase = true) ||
                key.equals("Cookie", ignoreCase = true)
            ) {
                "***"
            } else {
                "_"
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
