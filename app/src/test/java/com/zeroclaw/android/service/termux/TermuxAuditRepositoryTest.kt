/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import com.zeroclaw.android.data.local.dao.TermuxAuditDao
import com.zeroclaw.android.data.local.entity.TermuxAuditEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InMemoryTermuxAuditRepository")
class TermuxAuditRepositoryTest {
    private val clock =
        Clock.fixed(
            Instant.parse("2026-05-16T00:00:00Z"),
            ZoneOffset.UTC,
        )

    @Test
    fun `records requested command audits in insertion order`() =
        runTest {
            val repository =
                InMemoryTermuxAuditRepository(
                    clock = clock,
                    idGenerator = sequentialIds(),
                )

            val first =
                repository.recordRequested(
                    risk = TermuxCommandRisk.LOW,
                    commandPreview = "whoami",
                    reason = "diagnostic",
                    workingDirectory = null,
                    fingerprint = null,
                    id = null,
                )
            val second =
                repository.recordRequested(
                    risk = TermuxCommandRisk.HIGH,
                    commandPreview = "rm -rf tmp",
                    reason = "destructive",
                    workingDirectory = null,
                    fingerprint = null,
                    id = null,
                )

            assertEquals("audit-1", first.id)
            assertEquals(TermuxAuditState.REQUESTED, first.state)
            assertEquals(TermuxCommandRisk.LOW, first.risk)
            assertEquals(clock.instant(), first.requestedAt)
            assertEquals(listOf(first, second), repository.list())
        }

    @Test
    fun `transitions known records while preserving request timestamp`() =
        runTest {
            val repository =
                InMemoryTermuxAuditRepository(
                    clock = clock,
                    idGenerator = sequentialIds(),
                )
            val requested =
                repository.recordRequested(
                    risk = TermuxCommandRisk.MEDIUM,
                    commandPreview = "zero-assist-helper --status",
                    reason = null,
                    workingDirectory = null,
                    fingerprint = null,
                    id = null,
                )

            val denied =
                repository.transition(
                    id = requested.id,
                    state = TermuxAuditState.DENIED,
                    reason = "not allowlisted",
                )

            assertEquals(TermuxAuditState.DENIED, denied?.state)
            assertEquals("not allowlisted", denied?.reason)
            assertEquals(requested.requestedAt, denied?.requestedAt)
            assertEquals(denied, repository.get(requested.id))
        }

    @Test
    fun `returns null when transitioning unknown records`() =
        runTest {
            val repository =
                InMemoryTermuxAuditRepository(
                    clock = clock,
                    idGenerator = sequentialIds(),
                )

            assertNull(repository.transition("missing", TermuxAuditState.FAILED))
        }

    @Test
    fun `room repository persists requested and transitioned audit records`() =
        runTest {
            val dao = mockk<TermuxAuditDao>()
            var stored: TermuxAuditEntity? = null
            coEvery { dao.upsert(any()) } answers {
                stored = invocation.args.first() as TermuxAuditEntity
                Unit
            }
            coEvery { dao.get("audit-1") } answers { stored }
            coEvery { dao.listAll() } answers { listOfNotNull(stored) }
            val repository =
                RoomTermuxAuditRepository(
                    dao = dao,
                    clock = clock,
                    idGenerator = sequentialIds(),
                )

            val requested =
                repository.recordRequested(
                    risk = TermuxCommandRisk.HIGH,
                    commandPreview = "pkg install git",
                    reason = "Installs a package.",
                    workingDirectory = "/data/data/com.termux/files/home/.zero-assist/workspace",
                    fingerprint = "abc123",
                    id = null,
                )
            val approved =
                repository.transition(
                    id = requested.id,
                    state = TermuxAuditState.APPROVED,
                    reason = "User allowed once.",
                )

            assertEquals("audit-1", requested.id)
            assertEquals(TermuxAuditState.APPROVED, approved?.state)
            assertEquals("abc123", approved?.fingerprint)
            assertEquals(listOf(approved), repository.list())
            coVerify(exactly = 2) { dao.upsert(any()) }
        }

    @Test
    fun `command preview redacts bridge tokens`() {
        val preview =
            TermuxCommandPolicyInput(
                command = TermuxBridgeBootstrapRequestBuilder.DEFAULT_PYTHON_PATH,
                arguments =
                    listOf(
                        TermuxBridgeBootstrapRequestBuilder.DEFAULT_BRIDGE_SCRIPT_PATH,
                        "--port",
                        "8787",
                        "--token",
                        "secret-token",
                    ),
            ).toCommandPreview()

        assertEquals(
            "/data/data/com.termux/files/usr/bin/python3 " +
                "/data/data/com.termux/files/home/.zero-assist/termux_bridge.py " +
                "--port 8787 --token <redacted>",
            preview,
        )
        assertTrue(!preview.contains("secret-token"))
    }

    private fun sequentialIds(): () -> String {
        var index = 0
        return {
            index += 1
            "audit-$index"
        }
    }
}
