/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.oauth

import java.net.URLDecoder
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SpotifyOAuthManager")
class SpotifyOAuthManagerTest {
    @Test
    @DisplayName("generatePkceState produces unique verifier and state")
    fun `generatePkceState produces unique verifier and state`() {
        val first = SpotifyOAuthManager.generatePkceState()
        val second = SpotifyOAuthManager.generatePkceState()

        assertNotEquals(first.codeVerifier, second.codeVerifier)
        assertNotEquals(first.state, second.state)
        assertEquals(SHA256_DIGEST_LENGTH, Base64.getUrlDecoder().decode(first.codeChallenge).size)
    }

    @Test
    @DisplayName("buildAuthorizeUrl includes Spotify PKCE parameters and scopes")
    fun `buildAuthorizeUrl includes spotify pkce parameters and scopes`() {
        val pkce =
            PkceState(
                codeVerifier = "verifier",
                codeChallenge = "challenge",
                state = "state",
            )

        val url =
            SpotifyOAuthManager.buildAuthorizeUrl(
                pkce = pkce,
                clientId = "client-id",
                port = 1789,
            )

        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue("response_type=code" in url)
        assertTrue("client_id=client-id" in url)
        assertTrue("code_challenge=challenge" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("state=state" in url)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A1789%2Fauth%2Fcallback" in url)

        val decodedUrl = URLDecoder.decode(url, Charsets.UTF_8.name())
        assertTrue("user-read-playback-state" in decodedUrl)
        assertTrue("user-modify-playback-state" in decodedUrl)
        assertTrue("user-library-read" in decodedUrl)
        assertTrue("playlist-read-private" in decodedUrl)
    }

    @Test
    @DisplayName("buildAuthorizeUrl rejects blank client ID")
    fun `buildAuthorizeUrl rejects blank client id`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpotifyOAuthManager.buildAuthorizeUrl(
                pkce =
                    PkceState(
                        codeVerifier = "verifier",
                        codeChallenge = "challenge",
                        state = "state",
                    ),
                clientId = "",
            )
        }
    }

    @Test
    @DisplayName("buildTokenExchangeBody uses Spotify form body")
    fun `buildTokenExchangeBody uses spotify form body`() {
        val body =
            SpotifyOAuthManager.buildTokenExchangeBody(
                code = "auth code",
                codeVerifier = "code verifier",
                clientId = "client id",
                port = 1789,
            )

        assertTrue("grant_type=authorization_code" in body)
        assertTrue("code=auth%20code" in body)
        assertTrue("client_id=client%20id" in body)
        assertTrue("code_verifier=code%20verifier" in body)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A1789%2Fauth%2Fcallback" in body)
    }

    private companion object {
        private const val SHA256_DIGEST_LENGTH = 32
    }
}
