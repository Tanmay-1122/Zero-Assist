/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.oauth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Orchestrates Spotify's Authorization Code with PKCE flow.
 *
 * Spotify account linking runs as a public-client OAuth flow on Android, so
 * callers must provide a Spotify Developer Dashboard client ID but must never
 * bundle a client secret in the app.
 */
object SpotifyOAuthManager {
    private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val REDIRECT_URI_BASE = "http://127.0.0.1"
    private const val REDIRECT_PATH = "/auth/callback"
    private const val CODE_VERIFIER_BYTE_LENGTH = 64
    private const val STATE_NONCE_BYTE_LENGTH = 24
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MILLIS_PER_SECOND = 1000L

    /** Scopes needed for assistant playback control and user-library discovery. */
    val defaultScopes: List<String> =
        listOf(
            "user-read-playback-state",
            "user-read-currently-playing",
            "user-modify-playback-state",
            "user-library-read",
            "playlist-read-private",
        )

    /** Generates a fresh PKCE verifier, challenge, and CSRF state nonce. */
    fun generatePkceState(): PkceState {
        val verifierBytes = ByteArray(CODE_VERIFIER_BYTE_LENGTH)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = base64UrlEncode(verifierBytes)

        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray())
        val codeChallenge = base64UrlEncode(digest)

        val stateBytes = ByteArray(STATE_NONCE_BYTE_LENGTH)
        SecureRandom().nextBytes(stateBytes)

        return PkceState(
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
            state = base64UrlEncode(stateBytes),
        )
    }

    /**
     * Builds the Spotify authorization URL to open in a Custom Tab.
     *
     * @param pkce Fresh PKCE state from [generatePkceState].
     * @param clientId Spotify app client ID from the developer dashboard.
     * @param port Loopback callback server port.
     * @param scopes OAuth scopes to request.
     */
    fun buildAuthorizeUrl(
        pkce: PkceState,
        clientId: String,
        port: Int = OAuthCallbackServer.DEFAULT_PORT,
        scopes: List<String> = defaultScopes,
    ): String {
        require(clientId.isNotBlank()) { "Spotify client ID is required." }
        val params =
            linkedMapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "scope" to scopes.joinToString(" "),
                "redirect_uri" to redirectUri(port),
                "code_challenge_method" to "S256",
                "code_challenge" to pkce.codeChallenge,
                "state" to pkce.state,
            )
        val query =
            params.entries.joinToString("&") { (key, value) ->
                "${urlEncode(key)}=${urlEncode(value)}"
            }
        return "$AUTHORIZE_URL?$query"
    }

    /**
     * Exchanges a Spotify authorization code for access and refresh tokens.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
        clientId: String,
        port: Int = OAuthCallbackServer.DEFAULT_PORT,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): OAuthTokenResult =
        withContext(ioDispatcher) {
            val formBody =
                buildTokenExchangeBody(
                    code = code,
                    codeVerifier = codeVerifier,
                    clientId = clientId,
                    port = port,
                )
            val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", FORM_CONTENT_TYPE)
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }

                val statusCode = conn.responseCode
                if (statusCode !in HTTP_OK_RANGE) {
                    val errorBody =
                        try {
                            conn.errorStream?.bufferedReader()?.readText().orEmpty()
                        } catch (_: IOException) {
                            ""
                        }
                    throw OAuthExchangeException(
                        "Spotify token exchange failed: HTTP $statusCode - $errorBody",
                        httpStatusCode = statusCode,
                    )
                }

                parseTokenResponse(conn.inputStream.bufferedReader().readText())
            } catch (e: OAuthExchangeException) {
                throw e
            } catch (e: Exception) {
                throw OAuthExchangeException("Spotify token exchange failed", cause = e)
            } finally {
                conn.disconnect()
            }
        }

    internal fun redirectUri(port: Int): String = "$REDIRECT_URI_BASE:$port$REDIRECT_PATH"

    internal fun buildTokenExchangeBody(
        code: String,
        codeVerifier: String,
        clientId: String,
        port: Int,
    ): String {
        require(clientId.isNotBlank()) { "Spotify client ID is required." }
        return formEncode(
            listOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri(port),
                "client_id" to clientId,
                "code_verifier" to codeVerifier,
            ),
        )
    }

    private fun parseTokenResponse(responseBody: String): OAuthTokenResult {
        val json = JSONObject(responseBody)
        val expiresInSeconds = json.optLong("expires_in", 0L)
        return OAuthTokenResult(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() + expiresInSeconds * MILLIS_PER_SECOND,
        )
    }

    private fun formEncode(values: List<Pair<String, String>>): String =
        values.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

    private fun urlEncode(value: String): String =
        URLEncoder
            .encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)

    private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
    private val HTTP_OK_RANGE = 200..299
}
