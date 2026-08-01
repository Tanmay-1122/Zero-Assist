/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.zeroclaw.android.data.db.channel.ChannelConfigurationDao
import com.zeroclaw.android.model.ChannelConfiguration
import com.zeroclaw.android.model.ChannelCredentials
import com.zeroclaw.android.model.ChannelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Room-backed implementation of ChannelRepository.
 *
 * Provides persistent channel configuration storage with workspace isolation,
 * credential encryption, and sync scheduling.
 *
 * @param dao ChannelConfigurationDao for database access.
 * @param encryptionService Service for encrypting/decrypting credentials (optional).
 */
class RoomChannelRepository(
    private val dao: ChannelConfigurationDao,
    private val encryptionService: EncryptionService = AndroidKeystoreEncryptionService(),
) : ChannelRepository {

    override suspend fun createChannel(channel: ChannelConfiguration): String {
        return withContext(Dispatchers.IO) {
            val id = channel.id.ifEmpty { UUID.randomUUID().toString() }
            val createdChannel = channel.copy(
                id = id,
                createdAt = channel.createdAt.ifEmpty { Instant.now().toString() },
            )
            dao.insert(createdChannel)
            id
        }
    }

    override suspend fun updateChannel(channel: ChannelConfiguration) {
        withContext(Dispatchers.IO) {
            dao.update(channel)
        }
    }

    override suspend fun deleteChannel(channelId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteById(channelId)
        }
    }

    override suspend fun getChannel(channelId: String): ChannelConfiguration? {
        return withContext(Dispatchers.IO) {
            dao.getById(channelId)
        }
    }

    override fun observeChannelsByWorkspace(workspaceId: String): Flow<List<ChannelConfiguration>> {
        return dao.getByWorkspace(workspaceId)
    }

    override suspend fun getActiveChannels(workspaceId: String): List<ChannelConfiguration> {
        return withContext(Dispatchers.IO) {
            dao.getActiveChannelsByWorkspace(workspaceId)
        }
    }

    override suspend fun getChannelsByType(workspaceId: String, type: String): List<ChannelConfiguration> {
        return withContext(Dispatchers.IO) {
            dao.getByType(workspaceId, type)
        }
    }

    override suspend fun getChannelsNeedingSync(workspaceId: String): List<ChannelConfiguration> {
        return withContext(Dispatchers.IO) {
            dao.getChannelsNeedingSync(workspaceId)
        }
    }

    override suspend fun updateConnectionStatus(
        channelId: String,
        connected: Boolean,
        errorMessage: String?,
    ) {
        withContext(Dispatchers.IO) {
            val status = if (connected) "connected" else "disconnected"
            dao.updateConnectionStatus(
                channelId,
                status,
                if (connected) null else (errorMessage ?: "Connection failed"),
                Instant.now().toString(),
            )
        }
    }

    override suspend fun recordSync(
        channelId: String,
        itemsProcessed: Int,
        errors: Int,
    ) {
        withContext(Dispatchers.IO) {
            dao.updateLastSync(channelId, Instant.now().toString())
        }
    }

    override suspend fun storeCredentials(channelId: String, credentials: ChannelCredentials) {
        withContext(Dispatchers.IO) {
            val json = JSONObject()
            json.put("type", credentials.type)
            credentials.apiKey?.let { json.put("apiKey", it) }
            credentials.accessToken?.let { json.put("accessToken", it) }
            credentials.refreshToken?.let { json.put("refreshToken", it) }
            credentials.username?.let { json.put("username", it) }
            credentials.password?.let { json.put("password", it) }
            credentials.webhookUrl?.let { json.put("webhookUrl", it) }
            credentials.webhookSecret?.let { json.put("webhookSecret", it) }
            credentials.phoneNumber?.let { json.put("phoneNumber", it) }

            val encryptedJson = encryptionService.encrypt(json.toString())
            dao.updateCredentials(channelId, encryptedJson)
        }
    }

    override suspend fun getCredentials(channelId: String): ChannelCredentials? {
        return withContext(Dispatchers.IO) {
            val channel = dao.getById(channelId) ?: return@withContext null
            if (channel.credentials.isEmpty()) return@withContext null

            try {
                val decrypted = encryptionService.decrypt(channel.credentials)
                val json = JSONObject(decrypted)
                ChannelCredentials(
                    type = json.getString("type"),
                    apiKey = json.optNullableString("apiKey"),
                    accessToken = json.optNullableString("accessToken"),
                    refreshToken = json.optNullableString("refreshToken"),
                    username = json.optNullableString("username"),
                    password = json.optNullableString("password"),
                    webhookUrl = json.optNullableString("webhookUrl"),
                    webhookSecret = json.optNullableString("webhookSecret"),
                    phoneNumber = json.optNullableString("phoneNumber"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun updateSettings(channelId: String, settings: ChannelSettings) {
        withContext(Dispatchers.IO) {
            val json = JSONObject()
            settings.redditSubreddit?.let { json.put("redditSubreddit", it) }
            settings.twitterSearchQuery?.let { json.put("twitterSearchQuery", it) }
            settings.notionDatabaseId?.let { json.put("notionDatabaseId", it) }
            settings.voiceCallTranscription.let { json.put("voiceCallTranscription", it) }

            dao.updateSettings(channelId, json.toString())
        }
    }

    override suspend fun getSettings(channelId: String): ChannelSettings? {
        return withContext(Dispatchers.IO) {
            val channel = dao.getById(channelId) ?: return@withContext null
            if (channel.settings.isEmpty()) return@withContext ChannelSettings()

            try {
                val json = JSONObject(channel.settings)
                ChannelSettings(
                    redditSubreddit = json.optNullableString("redditSubreddit"),
                    redditSearchQuery = json.optNullableString("redditSearchQuery"),
                    twitterSearchQuery = json.optNullableString("twitterSearchQuery"),
                    notionDatabaseId = json.optNullableString("notionDatabaseId"),
                    voiceCallTranscription = json.optBoolean("voiceCallTranscription", true),
                )
            } catch (e: Exception) {
                ChannelSettings()
            }
        }
    }

    override suspend fun toggleChannelActive(channelId: String) {
        withContext(Dispatchers.IO) {
            dao.toggleActive(channelId)
        }
    }

    override suspend fun getChannelCount(workspaceId: String): Int {
        return withContext(Dispatchers.IO) {
            dao.getChannelCount(workspaceId)
        }
    }

    override suspend fun clearWorkspaceChannels(workspaceId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteByWorkspace(workspaceId)
        }
    }

    override suspend fun getChannelSyncStats(workspaceId: String): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            val allChannels = dao.getActiveChannelsByWorkspace(workspaceId)
            val connected = dao.getByConnectionStatus(workspaceId, "connected").size
            val disconnected = dao.getByConnectionStatus(workspaceId, "disconnected").size
            val error = dao.getByConnectionStatus(workspaceId, "error").size

            mapOf(
                "total" to allChannels.size,
                "connected" to connected,
                "disconnected" to disconnected,
                "error" to error,
                "byType" to ChannelRepository.AVAILABLE_TYPES.associateWith { type ->
                    allChannels.count { it.type == type }
                },
            )
        }
    }
}

/**
 * Encryption service for channel credential payloads.
 */
interface EncryptionService {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

/**
 * Android Keystore-backed AES-GCM credential encryption.
 *
 * Legacy plaintext values are still readable for migration safety: if a stored value does not carry
 * the current prefix, [decrypt] returns it unchanged so existing rows can be read and rewritten
 * through [encrypt] on the next credential save.
 */
class AndroidKeystoreEncryptionService : EncryptionService {
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(payload, destinationOffset = 0)
        ciphertext.copyInto(payload, destinationOffset = iv.size)
        return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    override fun decrypt(ciphertext: String): String {
        if (!ciphertext.startsWith(VERSION_PREFIX)) {
            return ciphertext
        }
        val payload = Base64.getDecoder().decode(ciphertext.removePrefix(VERSION_PREFIX))
        require(payload.size > IV_SIZE_BYTES) { "Encrypted channel credential payload is too short" }
        val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
        val encryptedBytes = payload.copyOfRange(IV_SIZE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keySpec =
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zero_assist_channel_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION_PREFIX = "za1:"
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    when {
        !has(key) -> null
        isNull(key) -> null
        else -> optString(key).ifEmpty { null }
    }
