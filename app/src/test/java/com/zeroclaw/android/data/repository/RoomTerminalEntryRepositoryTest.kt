/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.data.local.dao.TerminalEntryDao
import com.zeroclaw.android.data.local.entity.TerminalEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RoomTerminalEntryRepository")
class RoomTerminalEntryRepositoryTest {
    @Test
    fun `observes bounded recent scrollback`() = runTest {
        val dao = FakeTerminalEntryDao()
        val repository =
            RoomTerminalEntryRepository(
                dao = dao,
                ioScope = this,
                scrollbackLimit = 2,
            )

        dao.recentEntries.value =
            listOf(
                terminalEntity(id = 2, content = "two"),
                terminalEntity(id = 3, content = "three"),
            )

        val entries = repository.entries.first()

        assertEquals(2, dao.lastObservedLimit)
        assertEquals(listOf("two", "three"), entries.map { it.content })
    }

    @Test
    fun `coerces invalid scrollback limit to at least one`() = runTest {
        val dao = FakeTerminalEntryDao()

        RoomTerminalEntryRepository(
            dao = dao,
            ioScope = this,
            scrollbackLimit = 0,
        )

        assertEquals(1, dao.lastObservedLimit)
    }

    private fun terminalEntity(
        id: Long,
        content: String,
    ): TerminalEntryEntity =
        TerminalEntryEntity(
            id = id,
            content = content,
            entryType = "response",
            timestamp = id,
        )

    private class FakeTerminalEntryDao : TerminalEntryDao {
        val recentEntries = MutableStateFlow<List<TerminalEntryEntity>>(emptyList())
        var lastObservedLimit: Int? = null

        override fun observeAll(): Flow<List<TerminalEntryEntity>> =
            MutableStateFlow(emptyList())

        override fun observeRecent(limit: Int): Flow<List<TerminalEntryEntity>> {
            lastObservedLimit = limit
            return recentEntries
        }

        override suspend fun insert(entity: TerminalEntryEntity): Long = entity.id

        override suspend fun deleteAll() = Unit
    }
}
