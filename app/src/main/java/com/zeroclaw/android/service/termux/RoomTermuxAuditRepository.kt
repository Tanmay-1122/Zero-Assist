/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import com.zeroclaw.android.data.local.dao.TermuxAuditDao
import com.zeroclaw.android.data.local.entity.TermuxAuditEntity
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed Termux audit repository so approval decisions survive restarts.
 */
class RoomTermuxAuditRepository(
    private val dao: TermuxAuditDao,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : TermuxAuditRepository {
    override suspend fun recordRequested(
        risk: TermuxCommandRisk,
        commandPreview: String,
        reason: String?,
        workingDirectory: String?,
        fingerprint: String?,
        id: String?,
    ): TermuxAuditRecord =
        withContext(Dispatchers.IO) {
            val now = clock.instant()
            val record =
                TermuxAuditRecord(
                    id = id?.takeIf { it.isNotBlank() } ?: idGenerator(),
                    state = TermuxAuditState.REQUESTED,
                    risk = risk,
                    commandPreview = commandPreview.trim(),
                    requestedAt = now,
                    updatedAt = now,
                    reason = reason,
                    workingDirectory = workingDirectory,
                    fingerprint = fingerprint,
                )
            dao.upsert(record.toEntity())
            record
        }

    override suspend fun transition(
        id: String,
        state: TermuxAuditState,
        reason: String?,
    ): TermuxAuditRecord? =
        withContext(Dispatchers.IO) {
            val existing = dao.get(id)?.toDomain() ?: return@withContext null
            val updated =
                existing.copy(
                    state = state,
                    updatedAt = clock.instant(),
                    reason = reason ?: existing.reason,
                )
            dao.upsert(updated.toEntity())
            updated
        }

    override suspend fun get(id: String): TermuxAuditRecord? =
        withContext(Dispatchers.IO) {
            dao.get(id)?.toDomain()
        }

    override suspend fun list(): List<TermuxAuditRecord> =
        withContext(Dispatchers.IO) {
            dao.listAll().map { it.toDomain() }
        }

    override fun observeRecentCommands(limit: Int): Flow<List<TermuxAuditRecord>> =
        dao.observeRecent(limit).map { entities -> entities.map { it.toDomain() } }
}

private fun TermuxAuditRecord.toEntity(): TermuxAuditEntity =
    TermuxAuditEntity(
        id = id,
        state = state.name,
        risk = risk.name,
        commandPreview = commandPreview,
        requestedAtEpochMs = requestedAt.toEpochMilli(),
        updatedAtEpochMs = updatedAt.toEpochMilli(),
        reason = reason,
        workingDirectory = workingDirectory,
        fingerprint = fingerprint,
    )

private fun TermuxAuditEntity.toDomain(): TermuxAuditRecord =
    TermuxAuditRecord(
        id = id,
        state = enumValueOrDefault(state, TermuxAuditState.FAILED),
        risk = enumValueOrDefault(risk, TermuxCommandRisk.MEDIUM),
        commandPreview = commandPreview,
        requestedAt = Instant.ofEpochMilli(requestedAtEpochMs),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
        reason = reason,
        workingDirectory = workingDirectory,
        fingerprint = fingerprint,
    )

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    value: String,
    default: T,
): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)
