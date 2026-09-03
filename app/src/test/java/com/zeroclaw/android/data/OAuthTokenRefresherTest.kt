/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OAuthTokenRefresher] companion-object helpers.
 */
@DisplayName("OAuthTokenRefresher")
class OAuthTokenRefresherTest {
    @Test
    @DisplayName("refreshUrlForProvider returns Anthropic URL for anthropic provider")
    fun `refreshUrlForProvider returns anthropic url for anthropic`() {
        assertEquals(
            "https://claude.ai/api/oauth/token",
            OAuthTokenRefresher.refreshUrlForProvider("anthropic"),
        )
    }

    @Test
    @DisplayName("refreshUrlForProvider returns OpenAI URL for openai provider")
    fun `refreshUrlForProvider returns openai url for openai`() {
        assertEquals(
            "https://auth.openai.com/oauth/token",
            OAuthTokenRefresher.refreshUrlForProvider("openai"),
        )
    }

    @Test
    @DisplayName("refreshUrlForProvider returns Spotify URL for spotify provider")
    fun `refreshUrlForProvider returns spotify url for spotify`() {
        assertEquals(
            "https://accounts.spotify.com/api/token",
            OAuthTokenRefresher.refreshUrlForProvider("spotify"),
        )
    }

    @Test
    @DisplayName("refreshUrlForProvider defaults to Anthropic for unknown providers")
    fun `refreshUrlForProvider defaults to anthropic for unknown providers`() {
        assertEquals(
            "https://claude.ai/api/oauth/token",
            OAuthTokenRefresher.refreshUrlForProvider("gemini"),
        )
    }

    @Test
    @DisplayName("refreshContentTypeForProvider uses form encoding for Spotify")
    fun `refreshContentTypeForProvider uses form encoding for spotify`() {
        assertEquals(
            "application/x-www-form-urlencoded",
            OAuthTokenRefresher.refreshContentTypeForProvider("spotify"),
        )
    }

    @Test
    @DisplayName("refreshBodyForProvider builds Spotify PKCE refresh body")
    fun `refreshBodyForProvider builds spotify pkce refresh body`() {
        val body =
            OAuthTokenRefresher.refreshBodyForProvider(
                refreshToken = "refresh token",
                provider = "spotify",
                clientId = "client id",
            )

        assertTrue("grant_type=refresh_token" in body)
        assertTrue("refresh_token=refresh%20token" in body)
        assertTrue("client_id=client%20id" in body)
    }

    @Test
    @DisplayName("refreshBodyForProvider rejects Spotify refresh without client ID")
    fun `refreshBodyForProvider rejects spotify refresh without client id`() {
        assertThrows(IllegalArgumentException::class.java) {
            OAuthTokenRefresher.refreshBodyForProvider(
                refreshToken = "refresh",
                provider = "spotify",
            )
        }
    }
}
