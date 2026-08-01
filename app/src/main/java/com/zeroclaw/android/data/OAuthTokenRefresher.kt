/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Result of a successful OAuth token refresh.
 *
 * @property accessToken New access token.
 * @property refreshToken New single-use refresh token.
 * @property expiresAt Epoch milliseconds when [accessToken] expires.
 */
data class RefreshResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

/**
 * Exception thrown when an OAuth token refresh fails.
 *
 * @param message Human-readable error description.
 * @property httpStatusCode HTTP status code from the refresh endpoint, or 0 for
 *   non-HTTP errors (e.g. network failure, JSON parse error).
 * @param cause Optional underlying cause.
 */
class OAuthRefreshException(
    message: String,
    val httpStatusCode: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Refreshes OAuth access tokens for Anthropic, OpenAI, and Spotify providers.
 *
 * Anthropic OAuth refresh tokens are single-use: each successful refresh
 * issues a new (access token, refresh token) pair. OpenAI may or may not
 * return a new refresh token; when absent the existing token is reused. Spotify
 * refreshes use the public-client PKCE form body and require a client ID.
 * Callers must persist all returned tokens immediately.
 */
class OAuthTokenRefresher(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Exchanges a refresh token for a new access token pair.
     *
     * Safe to call from the main thread; switches to the injected
     * IO dispatcher internally.
     *
     * @param refreshToken The current single-use refresh token.
     * @param provider Provider identifier (e.g. "anthropic", "openai").
     *   Defaults to "anthropic" for backward compatibility.
     * @param clientId OAuth client ID for public-client providers such as
     *   Spotify. OpenAI uses Zero-Assist's bundled OAuth client by default.
     * @return A [RefreshResult] containing the new tokens and expiry.
     * @throws OAuthRefreshException if the refresh request fails.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun refresh(
        refreshToken: String,
        provider: String = "anthropic",
        clientId: String = "",
    ): RefreshResult =
        withContext(ioDispatcher) {
            val request =
                try {
                    buildRefreshRequest(refreshToken, provider, clientId)
                } catch (e: IllegalArgumentException) {
                    throw OAuthRefreshException(
                        e.message ?: "Invalid OAuth refresh request",
                        cause = e,
                    )
                }

            val url = URL(request.url)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", request.contentType)
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true

                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }

                val statusCode = conn.responseCode
                if (statusCode !in httpOkRange) {
                    val errorBody =
                        try {
                            conn.errorStream
                                ?.bufferedReader()
                                ?.readText()
                                .orEmpty()
                        } catch (_: IOException) {
                            ""
                        }
                    throw OAuthRefreshException(
                        "Refresh failed: HTTP $statusCode - $errorBody",
                        httpStatusCode = statusCode,
                    )
                }

                val responseBody = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)

                val expiresInSeconds = json.optLong("expires_in", 0L)
                RefreshResult(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token", refreshToken),
                    expiresAt = System.currentTimeMillis() + expiresInSeconds * MILLIS_PER_SECOND,
                )
            } catch (e: OAuthRefreshException) {
                throw e
            } catch (e: Exception) {
                throw OAuthRefreshException("Token refresh failed", cause = e)
            } finally {
                conn.disconnect()
            }
        }

    private val httpOkRange = 200..299

    /** Constants and helpers for [OAuthTokenRefresher]. */
    companion object {
        private const val ANTHROPIC_REFRESH_URL = "https://claude.ai/api/oauth/token"
        private const val OPENAI_REFRESH_URL = "https://auth.openai.com/oauth/token"
        private const val SPOTIFY_REFRESH_URL = "https://accounts.spotify.com/api/token"
        private const val OPENAI_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MILLIS_PER_SECOND = 1000L
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

        /**
         * Returns the OAuth token refresh URL for the given [provider].
         *
         * Recognized providers: "openai" and "spotify". All other values
         * (including "anthropic") fall back to the Anthropic refresh endpoint.
         *
         * @param provider Provider identifier.
         * @return The refresh endpoint URL.
         */
        fun refreshUrlForProvider(provider: String): String =
            when (provider.lowercase()) {
                "openai" -> OPENAI_REFRESH_URL
                "spotify" -> SPOTIFY_REFRESH_URL
                else -> ANTHROPIC_REFRESH_URL
            }

        /** Returns the request content type used by the provider refresh endpoint. */
        fun refreshContentTypeForProvider(provider: String): String =
            when (provider.lowercase()) {
                "spotify" -> FORM_CONTENT_TYPE
                else -> JSON_CONTENT_TYPE
            }

        /**
         * Builds the refresh request body for the provider.
         *
         * This is exposed for unit tests so provider-specific body shape stays
         * locked down without making network calls.
         */
        fun refreshBodyForProvider(
            refreshToken: String,
            provider: String,
            clientId: String = "",
        ): String =
            when (provider.lowercase()) {
                "spotify" ->
                    formEncode(
                        listOf(
                            "grant_type" to "refresh_token",
                            "refresh_token" to refreshToken,
                            "client_id" to
                                clientId.ifBlank {
                                    throw IllegalArgumentException(
                                        "Spotify OAuth refresh requires a client ID.",
                                    )
                                },
                        ),
                    )
                "openai" ->
                    jsonRefreshBody(
                        refreshToken = refreshToken,
                        clientId = clientId.ifBlank { OPENAI_CLIENT_ID },
                    )
                else -> jsonRefreshBody(refreshToken = refreshToken)
            }

        private fun buildRefreshRequest(
            refreshToken: String,
            provider: String,
            clientId: String,
        ): RefreshRequest =
            RefreshRequest(
                url = refreshUrlForProvider(provider),
                contentType = refreshContentTypeForProvider(provider),
                body = refreshBodyForProvider(refreshToken, provider, clientId),
            )

        private fun jsonRefreshBody(
            refreshToken: String,
            clientId: String = "",
        ): String =
            JSONObject()
                .apply {
                    put("grant_type", "refresh_token")
                    put("refresh_token", refreshToken)
                    if (clientId.isNotBlank()) {
                        put("client_id", clientId)
                    }
                }.toString()

        private fun formEncode(values: List<Pair<String, String>>): String =
            values.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

        private fun urlEncode(value: String): String =
            URLEncoder
                .encode(value, Charsets.UTF_8.name())
                .replace("+", "%20")
    }
}

private data class RefreshRequest(
    val url: String,
    val contentType: String,
    val body: String,
)
