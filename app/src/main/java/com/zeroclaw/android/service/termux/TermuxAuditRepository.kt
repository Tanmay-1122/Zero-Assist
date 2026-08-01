/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class TermuxAuditState {
    REQUESTED,
    APPROVED,
    DENIED,
    BLOCKED,
    EXECUTED,
    FAILED,
}

data class TermuxAuditRecord(
    val id: String,
    val state: TermuxAuditState,
    val risk: TermuxCommandRisk,
    val commandPreview: String,
    val requestedAt: Instant,
    val updatedAt: Instant = requestedAt,
    val reason: String? = null,
    val workingDirectory: String? = null,
    val fingerprint: String? = null,
)

interface TermuxAuditRepository {
    suspend fun recordRequested(
        risk: TermuxCommandRisk,
        commandPreview: String,
        reason: String? = null,
        workingDirectory: String? = null,
        fingerprint: String? = null,
        id: String? = null,
    ): TermuxAuditRecord

    suspend fun transition(
        id: String,
        state: TermuxAuditState,
        reason: String? = null,
    ): TermuxAuditRecord?

    suspend fun get(id: String): TermuxAuditRecord?

    suspend fun list(): List<TermuxAuditRecord>

    fun observeRecentCommands(limit: Int): Flow<List<TermuxAuditRecord>>
}

class InMemoryTermuxAuditRepository(
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : TermuxAuditRepository {
    private val lock = Any()
    private val records = linkedMapOf<String, TermuxAuditRecord>()

    override suspend fun recordRequested(
        risk: TermuxCommandRisk,
        commandPreview: String,
        reason: String?,
        workingDirectory: String?,
        fingerprint: String?,
        id: String?,
    ): TermuxAuditRecord =
        synchronized(lock) {
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
            records[record.id] = record
            record
        }

    override suspend fun transition(
        id: String,
        state: TermuxAuditState,
        reason: String?,
    ): TermuxAuditRecord? =
        synchronized(lock) {
            val existing = records[id] ?: return@synchronized null
            val updated =
                existing.copy(
                    state = state,
                    updatedAt = clock.instant(),
                    reason = reason ?: existing.reason,
                )
            records[id] = updated
            updated
        }

    override suspend fun get(id: String): TermuxAuditRecord? =
        synchronized(lock) {
            records[id]
        }

    override suspend fun list(): List<TermuxAuditRecord> =
        synchronized(lock) {
            records.values.toList()
        }

    override fun observeRecentCommands(limit: Int): Flow<List<TermuxAuditRecord>> =
        kotlinx.coroutines.flow.flow {
            emit(
                synchronized(lock) {
                    records.values.sortedByDescending { it.requestedAt }.take(limit)
                },
            )
        }
}

fun TermuxCommandPolicyInput.toCommandPreview(): String =
    buildList {
        add(command.trim())
        addAll(arguments.redactSensitiveArguments())
    }.joinToString(" ").trim()

private fun List<String>.redactSensitiveArguments(): List<String> {
    var redactNext = false
    return mapNotNull { rawArgument ->
        val argument = rawArgument.trim()
        if (argument.isBlank()) {
            null
        } else if (redactNext) {
            redactNext = false
            "<redacted>"
        } else if (argument == "--token") {
            redactNext = true
            argument
        } else if (argument.startsWith("--token=")) {
            "--token=<redacted>"
        } else {
            argument
        }
    }
}
