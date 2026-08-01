/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import com.zeroclaw.android.data.db.channel.ChannelConfigurationDao
import com.zeroclaw.android.model.ChannelConfiguration
import com.zeroclaw.android.model.ChannelCredentials
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RoomChannelRepositoryCredentialEncryptionTest {
    private lateinit var dao: ChannelConfigurationDao
    private lateinit var encryptionService: PrefixEncodingEncryptionService
    private lateinit var repository: RoomChannelRepository

    @BeforeEach
    fun setUp() {
        dao = mockk(relaxUnitFun = true)
        encryptionService = PrefixEncodingEncryptionService()
        repository = RoomChannelRepository(dao, encryptionService)
    }

    @Test
    fun `storeCredentials persists encrypted payload instead of plaintext json`() =
        runTest {
            val storedPayloads = mutableListOf<String>()
            coEvery { dao.updateCredentials("channel-1", capture(storedPayloads)) } just Runs

            repository.storeCredentials(
                "channel-1",
                ChannelCredentials(
                    type = "webhook",
                    apiKey = "api-secret",
                    webhookSecret = "hook-secret",
                ),
            )

            val stored = storedPayloads.single()
            assertTrue(stored.startsWith(PrefixEncodingEncryptionService.PREFIX))
            assertFalse(stored.contains("api-secret"))
            assertFalse(stored.contains("hook-secret"))
            coVerify(exactly = 1) { dao.updateCredentials("channel-1", stored) }
        }

    @Test
    fun `getCredentials decrypts encrypted payload`() =
        runTest {
            val encrypted =
                encryptionService.encrypt(
                    JSONObject()
                        .put("type", "reddit")
                        .put("accessToken", "access-token")
                        .put("refreshToken", "refresh-token")
                        .toString(),
                )
            coEvery { dao.getById("channel-1") } returns channel(credentials = encrypted)

            val credentials = repository.getCredentials("channel-1")

            assertEquals("reddit", credentials?.type)
            assertEquals("access-token", credentials?.accessToken)
            assertEquals("refresh-token", credentials?.refreshToken)
            assertNull(credentials?.apiKey)
        }

    @Test
    fun `getCredentials keeps legacy plaintext payloads readable for migration`() =
        runTest {
            val legacyPlaintext =
                JSONObject()
                    .put("type", "webhook")
                    .put("webhookUrl", "https://example.test/hook")
                    .put("webhookSecret", "legacy-secret")
                    .toString()
            coEvery { dao.getById("channel-1") } returns channel(credentials = legacyPlaintext)

            val credentials = repository.getCredentials("channel-1")

            assertEquals("webhook", credentials?.type)
            assertEquals("https://example.test/hook", credentials?.webhookUrl)
            assertEquals("legacy-secret", credentials?.webhookSecret)
        }

    private fun channel(credentials: String): ChannelConfiguration =
        ChannelConfiguration(
            id = "channel-1",
            type = "webhook",
            workspaceId = "default",
            name = "Webhook",
            credentials = credentials,
        )

    private class PrefixEncodingEncryptionService : EncryptionService {
        override fun encrypt(plaintext: String): String =
            PREFIX + Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

        override fun decrypt(ciphertext: String): String {
            if (!ciphertext.startsWith(PREFIX)) {
                return ciphertext
            }
            return String(Base64.getDecoder().decode(ciphertext.removePrefix(PREFIX)), Charsets.UTF_8)
        }

        companion object {
            const val PREFIX = "enc:"
        }
    }
}
