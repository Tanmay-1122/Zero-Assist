/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.FfiAuthProfile
import com.zeroclaw.ffi.FfiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AuthProfileRecord(
    val id: String,
    val provider: String,
    val profileName: String,
    val kind: String,
    val isActive: Boolean,
    val expiresAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

class AuthProfilesBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Throws(FfiException::class)
    suspend fun listProfiles(): List<AuthProfileRecord> =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.listAuthProfiles().map { it.toRecord() }
        }

    @Throws(FfiException::class)
    suspend fun removeProfile(
        provider: String,
        profileName: String,
    ) {
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.removeAuthProfile(provider, profileName)
        }
    }
}

private fun FfiAuthProfile.toRecord(): AuthProfileRecord =
    AuthProfileRecord(
        id = id,
        provider = provider,
        profileName = profileName,
        kind = kind,
        isActive = isActive,
        expiresAtMs = expiresAtMs,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
