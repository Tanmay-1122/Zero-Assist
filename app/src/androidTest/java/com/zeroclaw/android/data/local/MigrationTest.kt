/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Room database migrations.
 *
 * Uses [MigrationTestHelper] to verify that each migration correctly
 * transforms the schema. Each test creates a database at version N,
 * runs the migration to N+1, and validates the resulting schema by
 * querying the expected tables, columns, or indexes.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    /** Room migration test helper for schema validation. */
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZeroClawDatabase::class.java,
        )

    /**
     * Verifies schema version 1 creates all four expected tables.
     */
    @Test
    fun createDatabase_v1_hasFourTables() {
        val db = helper.createDatabase(TEST_DB, 1)
        val tables = queryTableNames(db)
        db.close()

        assert(tables.contains("agents")) { "Missing agents table" }
        assert(tables.contains("plugins")) { "Missing plugins table" }
        assert(tables.contains("log_entries")) { "Missing log_entries table" }
        assert(tables.contains("activity_events")) { "Missing activity_events table" }
    }

    /**
     * Verifies migration 1 to 2 adds the connected_channels table.
     */
    @Test
    fun migrate_1_to_2_addsConnectedChannelsTable() {
        helper.createDatabase(TEST_DB, 1).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                2,
                true,
                ZeroClawDatabase.MIGRATIONS[0],
            )
        val tables = queryTableNames(db)
        db.close()

        assert(tables.contains("connected_channels")) {
            "Migration 1→2 did not create connected_channels table"
        }
    }

    /**
     * Verifies migration 2 to 3 adds temperature and max_depth columns to agents.
     */
    @Test
    fun migrate_2_to_3_addsAgentColumns() {
        helper.createDatabase(TEST_DB, 1).close()
        helper
            .runMigrationsAndValidate(
                TEST_DB,
                2,
                true,
                ZeroClawDatabase.MIGRATIONS[0],
            ).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                3,
                true,
                ZeroClawDatabase.MIGRATIONS[1],
            )
        val columns = queryColumnNames(db, "agents")
        db.close()

        assert(columns.contains("temperature")) {
            "Migration 2→3 did not add temperature column"
        }
        assert(columns.contains("max_depth")) {
            "Migration 2→3 did not add max_depth column"
        }
    }

    /**
     * Verifies migration 3 to 4 adds the chat_messages table with a timestamp index.
     */
    @Test
    fun migrate_3_to_4_addsChatMessagesTable() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, ZeroClawDatabase.MIGRATIONS[0]).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, ZeroClawDatabase.MIGRATIONS[1]).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                4,
                true,
                ZeroClawDatabase.MIGRATIONS[2],
            )
        val tables = queryTableNames(db)
        val indexes = queryIndexNames(db)
        db.close()

        assert(tables.contains("chat_messages")) {
            "Migration 3→4 did not create chat_messages table"
        }
        assert(indexes.contains("index_chat_messages_timestamp")) {
            "Migration 3→4 did not create timestamp index"
        }
    }

    /**
     * Verifies migration 4 to 5 adds remote_version column to plugins.
     */
    @Test
    fun migrate_4_to_5_addsRemoteVersion() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, ZeroClawDatabase.MIGRATIONS[0]).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, ZeroClawDatabase.MIGRATIONS[1]).close()
        helper.runMigrationsAndValidate(TEST_DB, 4, true, ZeroClawDatabase.MIGRATIONS[2]).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                5,
                true,
                ZeroClawDatabase.MIGRATIONS[3],
            )
        val columns = queryColumnNames(db, "plugins")
        db.close()

        assert(columns.contains("remote_version")) {
            "Migration 4→5 did not add remote_version column"
        }
    }

    /**
     * Verifies migration 5 to 6 adds images_json column to chat_messages.
     */
    @Test
    fun migrate_5_to_6_addsImagesJson() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, ZeroClawDatabase.MIGRATIONS[0]).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, ZeroClawDatabase.MIGRATIONS[1]).close()
        helper.runMigrationsAndValidate(TEST_DB, 4, true, ZeroClawDatabase.MIGRATIONS[2]).close()
        helper.runMigrationsAndValidate(TEST_DB, 5, true, ZeroClawDatabase.MIGRATIONS[3]).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                6,
                true,
                ZeroClawDatabase.MIGRATIONS[4],
            )
        val columns = queryColumnNames(db, "chat_messages")
        db.close()

        assert(columns.contains("images_json")) {
            "Migration 5→6 did not add images_json column"
        }
    }

    /**
     * Verifies migration 6 to 7 adds unique index on connected_channels type column.
     */
    @Test
    fun migrate_6_to_7_addsUniqueIndex() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, ZeroClawDatabase.MIGRATIONS[0]).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, ZeroClawDatabase.MIGRATIONS[1]).close()
        helper.runMigrationsAndValidate(TEST_DB, 4, true, ZeroClawDatabase.MIGRATIONS[2]).close()
        helper.runMigrationsAndValidate(TEST_DB, 5, true, ZeroClawDatabase.MIGRATIONS[3]).close()
        helper.runMigrationsAndValidate(TEST_DB, 6, true, ZeroClawDatabase.MIGRATIONS[4]).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                7,
                true,
                ZeroClawDatabase.MIGRATIONS[5],
            )
        val indexes = queryIndexNames(db)
        db.close()

        assert(indexes.contains("index_connected_channels_type")) {
            "Migration 6→7 did not create unique index on connected_channels.type"
        }
    }

    /**
     * Verifies a full migration from version 1 through version 7 succeeds.
     */
    @Test
    fun migrate_1_to_7_fullChain() {
        helper.createDatabase(TEST_DB, 1).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                7,
                true,
                *ZeroClawDatabase.MIGRATIONS,
            )
        val tables = queryTableNames(db)
        db.close()

        assert(tables.contains("agents")) { "Missing agents table after full migration" }
        assert(tables.contains("plugins")) { "Missing plugins table after full migration" }
        assert(tables.contains("log_entries")) { "Missing log_entries table after full migration" }
        assert(tables.contains("activity_events")) { "Missing activity_events table" }
        assert(tables.contains("connected_channels")) { "Missing connected_channels table" }
        assert(tables.contains("chat_messages")) { "Missing chat_messages table" }
    }

    /**
     * Verifies migration 14 to 15 adds persistent agent history tables.
     */
    @Test
    fun migrate_14_to_16_addsConversationPersistenceTables() {
        helper.createDatabase(TEST_DB, 14).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                16,
                true,
                *ZeroClawDatabase.MIGRATIONS.drop(13).toTypedArray(),
            )
        val tables = queryTableNames(db)
        val conversationColumns = queryColumnNames(db, "conversations")
        val messageColumns = queryColumnNames(db, "agent_chat_messages")
        val familyColumns = queryColumnNames(db, "agent_families")
        val indexes = queryIndexNames(db)
        db.close()

        assert(tables.contains("conversations")) {
            "Migration 14→15 did not create conversations table"
        }
        assert(tables.contains("agent_chat_messages")) {
            "Migration 14→15 did not create agent_chat_messages table"
        }
        assert(tables.contains("agent_families")) {
            "Migration 14→15 did not create agent_families table"
        }
        assert(conversationColumns.contains("workspace_name")) {
            "Conversations table is missing workspace_name"
        }
        assert(conversationColumns.contains("title")) {
            "Conversations table is missing title"
        }
        assert(conversationColumns.contains("is_title_pending")) {
            "Conversations table is missing is_title_pending"
        }
        assert(messageColumns.contains("family_id")) {
            "Agent chat messages table is missing family_id"
        }
        assert(messageColumns.contains("message_type")) {
            "Agent chat messages table is missing message_type"
        }
        assert(familyColumns.contains("agent_ids_json")) {
            "Agent families table is missing agent_ids_json"
        }
        assert(familyColumns.contains("last_message_time_ms")) {
            "Agent families table is missing last_message_time_ms"
        }
        assert(indexes.contains("index_agent_chat_messages_family_id")) {
            "Agent chat messages table is missing family_id index"
        }
        assert(indexes.contains("index_agent_chat_messages_family_id_timestamp_ms")) {
            "Agent chat messages table is missing family_id/timestamp_ms index"
        }
    }

    /**
     * Verifies migration 16 to 17 adds the ZeroAI side tables without
     * clobbering the legacy advanced-memory table.
     */
    @Test
    fun migrate_16_to_17_addsZeroAiTables() {
        helper.createDatabase(TEST_DB, 16).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                17,
                true,
                ZeroClawDatabase.MIGRATIONS[15],
            )
        val tables = queryTableNames(db)
        val legacyMemoryColumns = queryColumnNames(db, "memory_facts")
        val mirrorMemoryColumns = queryColumnNames(db, "memory_facts_mirror")
        val indexes = queryIndexNames(db)
        db.close()

        assert(tables.contains("email_config")) {
            "Migration 16->17 did not create email_config"
        }
        assert(tables.contains("skill_execution_history")) {
            "Migration 16->17 did not create skill_execution_history"
        }
        assert(tables.contains("interaction_outcomes")) {
            "Migration 16->17 did not create interaction_outcomes"
        }
        assert(tables.contains("memory_facts")) {
            "Migration 16->17 should preserve the legacy memory_facts table"
        }
        assert(tables.contains("memory_facts_mirror")) {
            "Migration 16->17 did not create memory_facts_mirror"
        }
        assert(legacyMemoryColumns.contains("content")) {
            "Legacy memory_facts table no longer has the expected content column"
        }
        assert(mirrorMemoryColumns.contains("category")) {
            "memory_facts_mirror is missing category"
        }
        assert(mirrorMemoryColumns.contains("last_accessed_at")) {
            "memory_facts_mirror is missing last_accessed_at"
        }
        assert(indexes.contains("index_memory_facts_mirror_category")) {
            "memory_facts_mirror is missing the category index"
        }
        assert(indexes.contains("index_memory_facts_mirror_last_accessed_at")) {
            "memory_facts_mirror is missing the last_accessed_at index"
        }
    }

    /**
     * Verifies migration 17 to 18 removes the deprecated legacy agent config column.
     */
    @Test
    fun migrate_17_to_18_removesLegacyAgentConfigColumn() {
        helper.createDatabase(TEST_DB, 17).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                18,
                true,
                ZeroClawDatabase.MIGRATIONS[16],
            )
        val columns = queryColumnNames(db, "agents")
        db.close()

        assert(!columns.contains("droidrun_config_json")) {
            "Migration 17->18 should remove droidrun_config_json from agents"
        }
        assert(columns.contains("accent_color")) {
            "Migration 17->18 should preserve existing agent columns"
        }
    }

    /**
     * Verifies migration 18 to 19 creates the temporary Workflow V1 tables.
     */
    @Test
    fun migrate_18_to_19_addsWorkflowRollbackTables() {
        helper.createDatabase(TEST_DB, 18).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                19,
                true,
                ZeroClawDatabase.MIGRATIONS[17],
            )
        val tables = queryTableNames(db)
        val definitionColumns = queryColumnNames(db, "workflow_definitions")
        val runColumns = queryColumnNames(db, "workflow_runs")
        val indexes = queryIndexNames(db)
        db.close()

        assert(tables.contains("workflow_definitions")) {
            "Migration 18->19 should create workflow_definitions"
        }
        assert(tables.contains("workflow_runs")) {
            "Migration 18->19 should create workflow_runs"
        }
        assert(definitionColumns.contains("nodes_json")) {
            "workflow_definitions is missing nodes_json"
        }
        assert(definitionColumns.contains("connections_json")) {
            "workflow_definitions is missing connections_json"
        }
        assert(runColumns.contains("workflow_id")) {
            "workflow_runs is missing workflow_id"
        }
        assert(runColumns.contains("status")) {
            "workflow_runs is missing status"
        }
        assert(indexes.contains("index_workflow_definitions_updated_at_ms")) {
            "workflow_definitions is missing updated_at_ms index"
        }
        assert(indexes.contains("index_workflow_runs_workflow_id")) {
            "workflow_runs is missing workflow_id index"
        }
    }

    /**
     * Verifies migration 19 to 20 removes the rolled-back Workflow V1 tables.
     */
    @Test
    fun migrate_19_to_20_removesWorkflowRollbackTables() {
        helper.createDatabase(TEST_DB, 19).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                20,
                true,
                ZeroClawDatabase.MIGRATIONS[18],
            )
        val tables = queryTableNames(db)
        db.close()

        assert(!tables.contains("workflow_definitions")) {
            "Migration 19->20 should remove workflow_definitions"
        }
        assert(!tables.contains("workflow_runs")) {
            "Migration 19->20 should remove workflow_runs"
        }
    }

    /**
     * Verifies a direct 18 to 20 upgrade lands on the current schema.
     */
    @Test
    fun migrate_18_to_20_removesWorkflowRollbackTables() {
        helper.createDatabase(TEST_DB, 18).close()
        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                20,
                true,
                ZeroClawDatabase.MIGRATIONS[17],
                ZeroClawDatabase.MIGRATIONS[18],
            )
        val tables = queryTableNames(db)
        db.close()

        assert(!tables.contains("workflow_definitions")) {
            "Migration 18->20 should not leave workflow_definitions behind"
        }
        assert(!tables.contains("workflow_runs")) {
            "Migration 18->20 should not leave workflow_runs behind"
        }
    }

    private fun queryTableNames(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> {
        val cursor =
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'",
            )
        val tables = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            tables.add(cursor.getString(0))
        }
        cursor.close()
        return tables
    }

    private fun queryColumnNames(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): Set<String> {
        val cursor = db.query("PRAGMA table_info($table)")
        val columns = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        cursor.close()
        return columns
    }

    private fun queryIndexNames(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> {
        val cursor =
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' " +
                    "AND name NOT LIKE 'sqlite_%'",
            )
        val indexes = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            indexes.add(cursor.getString(0))
        }
        cursor.close()
        return indexes
    }

    /** Constants for [MigrationTest]. */
    companion object {
        private const val TEST_DB = "migration-test"
    }
}
