/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zeroclaw.android.data.local.dao.ActivityEventDao
import com.zeroclaw.android.data.local.dao.AgentChatMessageDao
import com.zeroclaw.android.data.local.dao.AgentFamilyDao
import com.zeroclaw.android.data.local.dao.AgentDao
import com.zeroclaw.android.data.local.dao.ConnectedChannelDao
import com.zeroclaw.android.data.local.dao.ConversationDao
import com.zeroclaw.android.data.local.dao.EmailConfigDao
import com.zeroclaw.android.data.local.dao.InteractionOutcomeDao
import com.zeroclaw.android.data.local.dao.LogEntryDao
import com.zeroclaw.android.data.local.dao.MemoryFactsDao
import com.zeroclaw.android.data.local.dao.PluginDao
import com.zeroclaw.android.data.local.dao.SkillExecutionDao
import com.zeroclaw.android.data.local.dao.TerminalEntryDao
import com.zeroclaw.android.data.local.dao.TermuxAuditDao
import com.zeroclaw.android.data.db.memory.MemoryFactDao
import com.zeroclaw.android.data.db.memory.MemoryTypeConverters
import com.zeroclaw.android.data.db.channel.ChannelConfigurationDao
import com.zeroclaw.android.data.db.agent.AskUserRequestDao
import com.zeroclaw.android.data.db.agent.AgentEscalationDao
import com.zeroclaw.android.data.db.agent.AgentSwarmDao
import com.zeroclaw.android.data.db.agent.LlmTaskDao
import com.zeroclaw.android.data.db.agent.ProjectIntelligenceDao
import com.zeroclaw.android.data.db.agent.AgentToolTraceDao
import com.zeroclaw.android.data.db.hardware.HardwareDeviceDao
import com.zeroclaw.android.data.db.hardware.GpioPinDao
import com.zeroclaw.android.data.db.hardware.SensorReadingDao
import com.zeroclaw.android.data.db.hardware.SensorAlertDao
import com.zeroclaw.android.data.db.hardware.ActuatorCommandDao
import com.zeroclaw.android.data.db.hardware.HardwareAuditLogDao
import com.zeroclaw.android.data.local.entity.ActivityEventEntity
import com.zeroclaw.android.data.local.entity.AgentChatMessageEntity
import com.zeroclaw.android.data.local.entity.AgentEntity
import com.zeroclaw.android.data.local.entity.AgentFamilyEntity
import com.zeroclaw.android.data.local.entity.ConnectedChannelEntity
import com.zeroclaw.android.data.local.entity.ConversationEntity
import com.zeroclaw.android.data.local.entity.EmailConfigEntity
import com.zeroclaw.android.data.local.entity.InteractionOutcomeEntity
import com.zeroclaw.android.data.local.entity.LogEntryEntity
import com.zeroclaw.android.data.local.entity.MemoryFactEntity
import com.zeroclaw.android.data.local.entity.PluginEntity
import com.zeroclaw.android.data.local.entity.SkillExecutionEntity
import com.zeroclaw.android.data.local.entity.TerminalEntryEntity
import com.zeroclaw.android.data.local.entity.TermuxAuditEntity
import com.zeroclaw.android.model.MemoryFact
import com.zeroclaw.android.model.ChannelConfiguration
import com.zeroclaw.android.model.AskUserRequest
import com.zeroclaw.android.model.AgentEscalation
import com.zeroclaw.android.model.AgentSwarm
import com.zeroclaw.android.model.LlmTask
import com.zeroclaw.android.model.ProjectIntelligence
import com.zeroclaw.android.model.AgentToolTrace
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.model.GpioPin
import com.zeroclaw.android.model.SensorReading
import com.zeroclaw.android.model.SensorAlert
import com.zeroclaw.android.model.ActuatorCommand
import com.zeroclaw.android.model.HardwareAuditLog
import com.zeroclaw.android.data.local.entity.GreetingHistoryEntity
import com.zeroclaw.android.data.local.entity.ScheduledTaskEntity
import com.zeroclaw.android.data.local.entity.ScheduledTaskRunEntity
import com.zeroclaw.android.data.local.dao.GreetingHistoryDao
import com.zeroclaw.android.data.local.dao.ScheduledTaskDao
import com.zeroclaw.android.data.local.dao.ScheduledTaskRunDao
import com.zeroclaw.android.data.db.greeting.GreetingTypeConverters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.Executors
import android.database.sqlite.SQLiteException
import com.zeroclaw.android.startup.AppStartupTrace
import com.zeroclaw.android.data.local.SeedData

/**
 * Room database for persistent storage of agents, plugins, log entries,
 * and activity events.
 *
 * Use [build] to create an instance with seed data callback.
 *
 * Migration strategy: explicit [Migration] objects in [MIGRATIONS] are
 * preferred for schema changes. [fallbackToDestructiveMigration] is
 * configured as a safety net during development to prevent crashes
 * when a migration is not yet written. Before production release,
 * all schema changes must have corresponding migrations.
 */
@TypeConverters(MemoryTypeConverters::class, GreetingTypeConverters::class)
@Database(
    entities = [
        AgentEntity::class,
        PluginEntity::class,
        LogEntryEntity::class,
        ActivityEventEntity::class,
        ConnectedChannelEntity::class,
        TerminalEntryEntity::class,
        MemoryFact::class,
        ChannelConfiguration::class,
        AskUserRequest::class,
        AgentEscalation::class,
        AgentSwarm::class,
        LlmTask::class,
        ProjectIntelligence::class,
        AgentToolTrace::class,
        HardwareDevice::class,
        GpioPin::class,
        SensorReading::class,
        SensorAlert::class,
        ActuatorCommand::class,
        HardwareAuditLog::class,
        AgentChatMessageEntity::class,
        AgentFamilyEntity::class,
        ConversationEntity::class,
        // Phase 1: New entities from ZeroAI-main
        EmailConfigEntity::class,
        SkillExecutionEntity::class,
        MemoryFactEntity::class,
        InteractionOutcomeEntity::class,
        TermuxAuditEntity::class,
        // Greeting history for dynamic welcome messages
        GreetingHistoryEntity::class,
        ScheduledTaskEntity::class,
        ScheduledTaskRunEntity::class,
    ],
    version = 25,
    exportSchema = true,
)
abstract class ZeroClawDatabase : RoomDatabase() {
    /** Data access object for agent operations. */
    abstract fun agentDao(): AgentDao

    /** Data access object for plugin operations. */
    abstract fun pluginDao(): PluginDao

    /** Data access object for log entry operations. */
    abstract fun logEntryDao(): LogEntryDao

    /** Data access object for activity event operations. */
    abstract fun activityEventDao(): ActivityEventDao

    /** Data access object for connected channel operations. */
    abstract fun connectedChannelDao(): ConnectedChannelDao

    /** Data access object for terminal REPL entry operations. */
    abstract fun terminalEntryDao(): TerminalEntryDao

    /** Data access object for memory fact operations. */
    abstract fun memoryFactDao(): MemoryFactDao

    /** Data access object for channel configuration operations. */
    abstract fun channelConfigurationDao(): ChannelConfigurationDao

    /** Data access object for user ask requests. */
    abstract fun askUserRequestDao(): AskUserRequestDao

    /** Data access object for escalations. */
    abstract fun agentEscalationDao(): AgentEscalationDao

    /** Data access object for agent swarms. */
    abstract fun agentSwarmDao(): AgentSwarmDao

    /** Data access object for LLM tasks. */
    abstract fun llmTaskDao(): LlmTaskDao

    /** Data access object for project intelligence. */
    abstract fun projectIntelligenceDao(): ProjectIntelligenceDao

    /** Data access object for agent tool traces. */
    abstract fun agentToolTraceDao(): AgentToolTraceDao

    /** Data access object for hardware devices. */
    abstract fun hardwareDeviceDao(): HardwareDeviceDao

    /** Data access object for GPIO pins. */
    abstract fun gpioPinDao(): GpioPinDao

    /** Data access object for sensor readings. */
    abstract fun sensorReadingDao(): SensorReadingDao

    /** Data access object for sensor alerts. */
    abstract fun sensorAlertDao(): SensorAlertDao

    /** Data access object for actuator commands. */
    abstract fun actuatorCommandDao(): ActuatorCommandDao

    /** Data access object for hardware audit logs. */
    abstract fun hardwareAuditLogDao(): HardwareAuditLogDao

    /** Data access object for scheduled tasks. */
    abstract fun scheduledTaskDao(): ScheduledTaskDao

    /** Data access object for scheduled task runs. */
    abstract fun scheduledTaskRunDao(): ScheduledTaskRunDao

    /** Data access object for persisted agent chat messages. */
    abstract fun agentChatMessageDao(): AgentChatMessageDao

    /** Data access object for persisted agent conversation families. */
    abstract fun agentFamilyDao(): AgentFamilyDao

    /** Data access object for persisted conversation metadata. */
    abstract fun conversationDao(): ConversationDao

    // ── Phase 1: New DAOs from ZeroAI-main ─────────────────────────────────

    /** Data access object for the singleton email configuration. */
    abstract fun emailConfigDao(): EmailConfigDao

    /** Data access object for skill execution history. */
    abstract fun skillExecutionDao(): SkillExecutionDao

    /** Data access object for Android-side memory fact mirror. */
    abstract fun memoryFactsDao(): MemoryFactsDao

    /** Data access object for interaction outcome statistics. */
    abstract fun interactionOutcomeDao(): InteractionOutcomeDao

    /** Data access object for durable Termux command audit records. */
    abstract fun termuxAuditDao(): TermuxAuditDao

    /** Data access object for greeting history. */
    abstract fun greetingHistoryDao(): GreetingHistoryDao

    /** Factory and constants for [ZeroClawDatabase]. */
    companion object {
        /** Database file name. */
        private const val DATABASE_NAME = "zeroclaw.db"
        /** Delay in milliseconds for waiting instance initialization. */
        private const val INSTANCE_INIT_POLL_DELAY_MS = 10L

        /** Migration from schema version 1 to 2: adds the connected_channels table. */
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `connected_channels` (
                            `id` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `is_enabled` INTEGER NOT NULL,
                            `config_json` TEXT NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                }
            }

        /** Migration from schema version 2 to 3: adds temperature and max_depth to agents. */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE agents ADD COLUMN temperature REAL")
                    db.execSQL(
                        "ALTER TABLE agents ADD COLUMN max_depth INTEGER NOT NULL DEFAULT 3",
                    )
                }
            }

        /** Migration from schema version 3 to 4: adds the chat_messages table. */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_messages` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `content` TEXT NOT NULL,
                            `is_from_user` INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)",
                    )
                }
            }

        /** Migration from schema version 4 to 5: adds remote_version column to plugins. */
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE plugins ADD COLUMN remote_version TEXT")
                }
            }

        /** Migration from schema version 5 to 6: adds images_json column to chat_messages. */
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE chat_messages ADD COLUMN images_json TEXT DEFAULT NULL",
                    )
                }
            }

        /** Migration from schema version 6 to 7: adds unique index on connected_channels.type. */
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_connected_channels_type` ON `connected_channels` (`type`)",
                    )
                }
            }

        /** Migration from schema version 7 to 8: adds the terminal_entries table. */
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `terminal_entries` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `content` TEXT NOT NULL,
                            `entry_type` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `image_uris` TEXT NOT NULL DEFAULT '[]'
                        )
                        """.trimIndent(),
                    )
                }
            }

        /** Migration from schema version 8 to 9: drops the deprecated chat_messages table. */
        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `chat_messages`")
                }
            }

        /** Migration from schema version 9 to 10: inserts official plugin rows. */
        @Suppress("LongMethod")
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    val officialPlugins =
                        listOf(
                            arrayOf(
                                "official-web-search",
                                "Web Search",
                                "Search the web via DuckDuckGo or Brave.",
                                "1.0.0",
                                "Zero-Assist",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-web-fetch",
                                "Web Fetch",
                                "Fetch and read web page content.",
                                "1.0.0",
                                "Zero-Assist",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-http-request",
                                "HTTP Request",
                                "Make HTTP calls to external APIs.",
                                "1.0.0",
                                "Zero-Assist",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-browser",
                                "Browser",
                                "Browse and interact with web pages.",
                                "1.0.0",
                                "Zero-Assist",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-composio",
                                "Composio",
                                "Third-party tool integrations via Composio.",
                                "1.0.0",
                                "Zero-Assist",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-vision",
                                "Vision",
                                "Process images for multimodal queries.",
                                "1.0.0",
                                "Zero-Assist",
                                "TOOL",
                                1,
                                1,
                                "{}",
                            ),
                            arrayOf(
                                "official-transcription",
                                "Transcription",
                                "Transcribe audio via Whisper-compatible API.",
                                "1.0.0",
                                "Zero-Assist",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-query-classification",
                                "Query Classification",
                                "Classify queries for intelligent model routing.",
                                "1.0.0",
                                "Zero-Assist",
                                "OTHER",
                                1,
                                0,
                                "{}",
                            ),
                        )
                    for (p in officialPlugins) {
                        db.execSQL(
                            """INSERT OR IGNORE INTO plugins
                               (id, name, description, version, author, category,
                                is_installed, is_enabled, config_json)
                               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                            p,
                        )
                    }
                }
            }

        /** Migration from schema version 10 to 11: adds a legacy per-agent config column. */
        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE agents ADD COLUMN droidrun_config_json TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        /** Migration from schema version 11 to 12: adds multi-agent upgrade fields. */
        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE agents ADD COLUMN role TEXT NOT NULL DEFAULT 'GENERAL'")
                    db.execSQL("ALTER TABLE agents ADD COLUMN avatar TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE agents ADD COLUMN tags_json TEXT NOT NULL DEFAULT '[]'")
                    db.execSQL("ALTER TABLE agents ADD COLUMN is_master INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE agents ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE agents ADD COLUMN template_id TEXT")
                    db.execSQL("ALTER TABLE agents ADD COLUMN accent_color INTEGER NOT NULL DEFAULT -10420225")
                }
            }

        /** Migration from schema version 12 to 13: adds agent tools tables. */
        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // AskUserRequest table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `agent_asks` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `agentId` TEXT NOT NULL,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `question` TEXT NOT NULL,
                            `questionType` TEXT NOT NULL DEFAULT 'text',
                            `choicesJson` TEXT,
                            `isBlocking` INTEGER NOT NULL DEFAULT 1,
                            `timeoutSeconds` INTEGER NOT NULL DEFAULT 300,
                            `userResponse` TEXT,
                            `respondedAt` TEXT,
                            `createdAt` TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )

                    // AgentEscalation table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `agent_escalations` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `agentId` TEXT NOT NULL,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `escalationType` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `targetRole` TEXT,
                            `assignedTo` TEXT,
                            `priority` TEXT NOT NULL DEFAULT 'normal',
                            `status` TEXT NOT NULL DEFAULT 'pending',
                            `resolution` TEXT,
                            `resolvedAt` TEXT,
                            `createdAt` TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )

                    // AgentSwarm table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `agent_swarms` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `name` TEXT NOT NULL,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `agentIdsJson` TEXT NOT NULL,
                            `coordinatorAgentId` TEXT NOT NULL,
                            `strategy` TEXT NOT NULL DEFAULT 'sequential',
                            `description` TEXT NOT NULL DEFAULT '',
                            `isActive` INTEGER NOT NULL DEFAULT 1,
                            `createdAt` TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )

                    // LlmTask table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `llm_tasks` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `agentId` TEXT NOT NULL,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `taskName` TEXT NOT NULL,
                            `description` TEXT NOT NULL DEFAULT '',
                            `instructions` TEXT NOT NULL,
                            `contextData` TEXT,
                            `targetModel` TEXT NOT NULL DEFAULT 'gpt4',
                            `priority` TEXT NOT NULL DEFAULT 'normal',
                            `status` TEXT NOT NULL DEFAULT 'pending',
                            `result` TEXT,
                            `errorMessage` TEXT,
                            `estimatedTokens` INTEGER NOT NULL DEFAULT 5000,
                            `actualTokens` INTEGER NOT NULL DEFAULT 0,
                            `createdAt` TEXT NOT NULL,
                            `startedAt` TEXT,
                            `completedAt` TEXT
                        )
                        """.trimIndent(),
                    )

                    // ProjectIntelligence table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `project_intel` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `sourceWorkspaceId` TEXT NOT NULL,
                            `targetWorkspaceId` TEXT NOT NULL,
                            `topicName` TEXT NOT NULL,
                            `contentSummary` TEXT NOT NULL,
                            `contentFull` TEXT NOT NULL,
                            `sourceAgentId` TEXT,
                            `relevanceScore` REAL NOT NULL DEFAULT 0.0,
                            `accessLevel` TEXT NOT NULL DEFAULT 'workspace',
                            `createdAt` TEXT NOT NULL,
                            `lastAccessedAt` TEXT
                        )
                        """.trimIndent(),
                    )

                    // AgentToolTrace table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `agent_tool_traces` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `agentId` TEXT NOT NULL,
                            `toolName` TEXT NOT NULL,
                            `toolRequestId` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `response` TEXT,
                            `errorMessage` TEXT,
                            `durationMs` INTEGER NOT NULL DEFAULT 0,
                            `createdAt` TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )

                    // Create indices for performance
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_agent_asks_workspace` ON `agent_asks` (`workspaceId`, `userResponse`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_agent_escalations_workspace` ON `agent_escalations` (`workspaceId`, `status`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_agent_swarms_workspace` ON `agent_swarms` (`workspaceId`, `isActive`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_llm_tasks_workspace` ON `llm_tasks` (`workspaceId`, `status`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_project_intel_target` ON `project_intel` (`targetWorkspaceId`, `accessLevel`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_agent_tool_traces_agent` ON `agent_tool_traces` (`agentId`, `toolName`)")
                }
            }

        /** Migration from schema version 13 to 14: adds hardware expansion tables. */
        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // HardwareDevice table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `hardware_devices` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `name` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `firmwareVersion` TEXT,
                            `connectionStatus` TEXT NOT NULL DEFAULT 'disconnected',
                            `ipAddress` TEXT,
                            `macAddress` TEXT,
                            `serialNumber` TEXT,
                            `lastSeen` TEXT,
                            `capabilities` TEXT NOT NULL DEFAULT '{}',
                            `configJson` TEXT NOT NULL DEFAULT '{}'
                        )
                        """.trimIndent(),
                    )

                    // GpioPin table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `gpio_pins` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `deviceId` TEXT NOT NULL,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `pinNumber` INTEGER NOT NULL,
                            `label` TEXT,
                            `mode` TEXT NOT NULL DEFAULT 'input',
                            `state` INTEGER NOT NULL DEFAULT 0,
                            `frequency` INTEGER,
                            `pullMode` TEXT,
                            `isActive` INTEGER NOT NULL DEFAULT 0,
                            `lastUpdated` TEXT,
                            `sensorType` TEXT
                        )
                        """.trimIndent(),
                    )

                    // SensorReading table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `sensor_readings` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `deviceId` TEXT NOT NULL,
                            `pinId` TEXT,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `sensorType` TEXT NOT NULL,
                            `value` REAL NOT NULL,
                            `unit` TEXT NOT NULL,
                            `timestamp` TEXT NOT NULL,
                            `isAlert` INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )

                    // SensorAlert table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `sensor_alerts` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `deviceId` TEXT NOT NULL,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `sensorType` TEXT NOT NULL,
                            `alertName` TEXT NOT NULL,
                            `thresholdType` TEXT NOT NULL,
                            `thresholdValue` REAL NOT NULL,
                            `thresholdMax` REAL,
                            `isActive` INTEGER NOT NULL DEFAULT 1,
                            `lastTriggered` TEXT,
                            `createdAt` TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )

                    // ActuatorCommand table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `actuator_commands` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `deviceId` TEXT NOT NULL,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `pinId` TEXT,
                            `commandType` TEXT NOT NULL,
                            `value` INTEGER,
                            `durationMs` INTEGER,
                            `status` TEXT NOT NULL DEFAULT 'pending',
                            `result` TEXT,
                            `isScheduled` INTEGER NOT NULL DEFAULT 0,
                            `scheduledTime` TEXT,
                            `createdAt` TEXT NOT NULL,
                            `executedAt` TEXT
                        )
                        """.trimIndent(),
                    )

                    // HardwareAuditLog table
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `hardware_audit_logs` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `deviceId` TEXT NOT NULL,
                            `workspaceId` TEXT NOT NULL DEFAULT 'default',
                            `action` TEXT NOT NULL,
                            `details` TEXT,
                            `status` TEXT NOT NULL DEFAULT 'success',
                            `errorMessage` TEXT,
                            `timestamp` TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )

                    // Create indices for performance
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_hardware_devices_workspace` ON `hardware_devices` (`workspaceId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_hardware_devices_status` ON `hardware_devices` (`connectionStatus`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_gpio_pins_device` ON `gpio_pins` (`deviceId`, `workspaceId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_gpio_pins_mode` ON `gpio_pins` (`mode`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sensor_readings_device` ON `sensor_readings` (`deviceId`, `workspaceId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sensor_readings_type` ON `sensor_readings` (`sensorType`, `timestamp`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sensor_alerts_device` ON `sensor_alerts` (`deviceId`, `isActive`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_actuator_commands_device` ON `actuator_commands` (`deviceId`, `status`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_actuator_commands_scheduled` ON `actuator_commands` (`isScheduled`, `scheduledTime`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_hardware_audit_logs_device` ON `hardware_audit_logs` (`deviceId`, `timestamp`)")
                }
            }

        /** Migration from schema version 14 to 15: adds persistent agent chat history tables. */
        private val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `agent_chat_messages` (
                            `id` TEXT NOT NULL,
                            `family_id` TEXT NOT NULL,
                            `sender_id` TEXT NOT NULL,
                            `sender_name` TEXT NOT NULL,
                            `sender_avatar` TEXT NOT NULL,
                            `sender_color` INTEGER NOT NULL,
                            `sender_role` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `message_type` TEXT NOT NULL,
                            `timestamp_ms` INTEGER NOT NULL,
                            `target_agent_id` TEXT,
                            `requires_approval` INTEGER NOT NULL,
                            `approval_state` TEXT NOT NULL,
                            `is_streaming` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_agent_chat_messages_family_id` ON `agent_chat_messages` (`family_id`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_agent_chat_messages_family_id_timestamp_ms` ON `agent_chat_messages` (`family_id`, `timestamp_ms`)",
                    )

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `agent_families` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `agent_ids_json` TEXT NOT NULL,
                            `created_at_ms` INTEGER NOT NULL,
                            `last_message_time_ms` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_agent_families_last_message_time_ms` ON `agent_families` (`last_message_time_ms`)",
                    )

                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `conversations` (
                            `id` TEXT NOT NULL,
                            `workspace_name` TEXT NOT NULL,
                            `primary_agent_name` TEXT NOT NULL,
                            `preview` TEXT NOT NULL,
                            `title` TEXT,
                            `is_title_pending` INTEGER NOT NULL,
                            `created_at_ms` INTEGER NOT NULL,
                            `last_message_at_ms` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_conversations_last_message_at_ms` ON `conversations` (`last_message_at_ms`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_conversations_workspace_name` ON `conversations` (`workspace_name`)",
                    )
                }
            }

        /**
         * Migration from schema version 15 to 16: normalizes agent chat message
         * storage for dev databases that carried forward stale indices from
         * pre-release builds.
         */
        private val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `agent_chat_messages_normalized` (
                            `id` TEXT NOT NULL,
                            `family_id` TEXT NOT NULL,
                            `sender_id` TEXT NOT NULL,
                            `sender_name` TEXT NOT NULL,
                            `sender_avatar` TEXT NOT NULL,
                            `sender_color` INTEGER NOT NULL,
                            `sender_role` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `message_type` TEXT NOT NULL,
                            `timestamp_ms` INTEGER NOT NULL,
                            `target_agent_id` TEXT,
                            `requires_approval` INTEGER NOT NULL,
                            `approval_state` TEXT NOT NULL,
                            `is_streaming` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO `agent_chat_messages_normalized` (
                            `id`,
                            `family_id`,
                            `sender_id`,
                            `sender_name`,
                            `sender_avatar`,
                            `sender_color`,
                            `sender_role`,
                            `content`,
                            `message_type`,
                            `timestamp_ms`,
                            `target_agent_id`,
                            `requires_approval`,
                            `approval_state`,
                            `is_streaming`
                        )
                        SELECT
                            `id`,
                            `family_id`,
                            `sender_id`,
                            `sender_name`,
                            `sender_avatar`,
                            `sender_color`,
                            `sender_role`,
                            `content`,
                            `message_type`,
                            `timestamp_ms`,
                            `target_agent_id`,
                            `requires_approval`,
                            `approval_state`,
                            `is_streaming`
                        FROM `agent_chat_messages`
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE `agent_chat_messages`")
                    db.execSQL(
                        "ALTER TABLE `agent_chat_messages_normalized` RENAME TO `agent_chat_messages`",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_agent_chat_messages_family_id` ON `agent_chat_messages` (`family_id`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_agent_chat_messages_family_id_timestamp_ms` ON `agent_chat_messages` (`family_id`, `timestamp_ms`)",
                    )
                }
            }

        /**
         * Ordered array of schema migrations.
         *
         * Add new [Migration] instances here as the schema evolves.
         * Each migration covers a single version increment (e.g. 1->2).
         */
        /**
         * Migration from schema version 16 to 17 (Phase 1 -- ZeroAI-main port):
         * Adds email_config, skill_execution_history, memory_facts_mirror,
         * and interaction_outcomes tables without touching any existing data.
         */
        private val MIGRATION_16_17 =
            object : Migration(16, 17) {
                @Suppress("LongMethod")
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `email_config` (" +
                            "`id` INTEGER NOT NULL, " +
                            "`imap_host` TEXT NOT NULL DEFAULT '', " +
                            "`imap_port` INTEGER NOT NULL DEFAULT 993, " +
                            "`smtp_host` TEXT NOT NULL DEFAULT '', " +
                            "`smtp_port` INTEGER NOT NULL DEFAULT 465, " +
                            "`address` TEXT NOT NULL DEFAULT '', " +
                            "`check_times` TEXT NOT NULL DEFAULT '', " +
                            "`is_enabled` INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `skill_execution_history` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`skill_name` TEXT NOT NULL, `tool_name` TEXT NOT NULL, " +
                            "`status` TEXT NOT NULL, `input_summary` TEXT, " +
                            "`output_summary` TEXT, `error_message` TEXT, " +
                            "`started_at` INTEGER NOT NULL, `completed_at` INTEGER, " +
                            "`duration_ms` INTEGER)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_seh_skill_started` ON `skill_execution_history` (`skill_name`, `started_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_seh_started` ON `skill_execution_history` (`started_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_seh_status` ON `skill_execution_history` (`status`)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `memory_facts_mirror` (" +
                            "`id` TEXT NOT NULL PRIMARY KEY, " +
                            "`key` TEXT NOT NULL, " +
                            "`content_preview` TEXT NOT NULL, " +
                            "`category` TEXT NOT NULL, " +
                            "`tags` TEXT NOT NULL, " +
                            "`confidence` REAL NOT NULL, " +
                            "`source` TEXT NOT NULL, " +
                            "`access_count` INTEGER NOT NULL, " +
                            "`created_at` INTEGER NOT NULL, " +
                            "`last_accessed_at` INTEGER, " +
                            "`decay_half_life_days` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_facts_mirror_category` ON `memory_facts_mirror` (`category`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_facts_mirror_last_accessed_at` ON `memory_facts_mirror` (`last_accessed_at`)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `interaction_outcomes` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`route_hint` TEXT NOT NULL, " +
                            "`provider` TEXT NOT NULL, " +
                            "`model` TEXT NOT NULL, " +
                            "`outcome` TEXT NOT NULL, " +
                            "`tool_call_count` INTEGER NOT NULL, " +
                            "`latency_ms` INTEGER NOT NULL, " +
                            "`created_at` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_interaction_outcomes_provider` ON `interaction_outcomes` (`provider`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_interaction_outcomes_created_at` ON `interaction_outcomes` (`created_at`)")
                }
            }

        /**
         * Migration from schema version 17 to 18: removes the deprecated
         * legacy per-agent config column from agents.
         */
        private val MIGRATION_17_18 =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `agents_new` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `provider` TEXT NOT NULL,
                            `model_name` TEXT NOT NULL,
                            `is_enabled` INTEGER NOT NULL,
                            `system_prompt` TEXT NOT NULL,
                            `channels_json` TEXT NOT NULL,
                            `temperature` REAL,
                            `max_depth` INTEGER NOT NULL DEFAULT 3,
                            `role` TEXT NOT NULL DEFAULT 'GENERAL',
                            `avatar` TEXT NOT NULL DEFAULT '',
                            `tags_json` TEXT NOT NULL DEFAULT '[]',
                            `is_master` INTEGER NOT NULL DEFAULT 0,
                            `priority` INTEGER NOT NULL DEFAULT 0,
                            `template_id` TEXT,
                            `accent_color` INTEGER NOT NULL DEFAULT -10420225,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO `agents_new` (
                            `id`,
                            `name`,
                            `provider`,
                            `model_name`,
                            `is_enabled`,
                            `system_prompt`,
                            `channels_json`,
                            `temperature`,
                            `max_depth`,
                            `role`,
                            `avatar`,
                            `tags_json`,
                            `is_master`,
                            `priority`,
                            `template_id`,
                            `accent_color`
                        )
                        SELECT
                            `id`,
                            `name`,
                            `provider`,
                            `model_name`,
                            `is_enabled`,
                            `system_prompt`,
                            `channels_json`,
                            `temperature`,
                            `max_depth`,
                            `role`,
                            `avatar`,
                            `tags_json`,
                            `is_master`,
                            `priority`,
                            `template_id`,
                            `accent_color`
                        FROM `agents`
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE `agents`")
                    db.execSQL("ALTER TABLE `agents_new` RENAME TO `agents`")
                }
            }

        /** Migration from schema version 18 to 19: adds Workflow V1 definitions and run history tables. */
        private val MIGRATION_18_19 =
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `workflow_definitions` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `enabled` INTEGER NOT NULL,
                            `nodes_json` TEXT NOT NULL,
                            `connections_json` TEXT NOT NULL,
                            `created_at_ms` INTEGER NOT NULL,
                            `updated_at_ms` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_workflow_definitions_updated_at_ms` " +
                            "ON `workflow_definitions` (`updated_at_ms`)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `workflow_runs` (
                            `id` TEXT NOT NULL,
                            `workflow_id` TEXT NOT NULL,
                            `trigger_type` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `started_at_ms` INTEGER NOT NULL,
                            `finished_at_ms` INTEGER,
                            `input_json` TEXT NOT NULL,
                            `output_json` TEXT NOT NULL,
                            `error_message` TEXT,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`workflow_id`) REFERENCES `workflow_definitions`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflow_id` " +
                            "ON `workflow_runs` (`workflow_id`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_workflow_runs_started_at_ms` " +
                            "ON `workflow_runs` (`started_at_ms`)",
                    )
                }
            }

        /** Migration from schema version 19 to 20: removes workflow tables after feature rollback. */
        private val MIGRATION_19_20 =
            object : Migration(19, 20) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `workflow_runs`")
                    db.execSQL("DROP TABLE IF EXISTS `workflow_definitions`")
                }
            }

        /** Migration from schema version 20 to 21: adds per-agent thinking level. */
        private val MIGRATION_20_21 =
            object : Migration(20, 21) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `agents` ADD COLUMN `thinking_level` TEXT NOT NULL DEFAULT 'HIGH'")
                }
            }

        /** Migration from schema version 21 to 22: adds durable Termux command audits. */
        private val MIGRATION_21_22 =
            object : Migration(21, 22) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `termux_audit_records` (
                            `id` TEXT NOT NULL,
                            `state` TEXT NOT NULL,
                            `risk` TEXT NOT NULL,
                            `command_preview` TEXT NOT NULL,
                            `requested_at_epoch_ms` INTEGER NOT NULL,
                            `updated_at_epoch_ms` INTEGER NOT NULL,
                            `reason` TEXT,
                            `working_directory` TEXT,
                            `fingerprint` TEXT,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_termux_audit_records_state` " +
                            "ON `termux_audit_records` (`state`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_termux_audit_records_risk` " +
                            "ON `termux_audit_records` (`risk`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_termux_audit_records_updated_at_epoch_ms` " +
                            "ON `termux_audit_records` (`updated_at_epoch_ms`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_termux_audit_records_fingerprint` " +
                            "ON `termux_audit_records` (`fingerprint`)",
                    )
                }
            }

        /** Migration from schema version 22 to 23: adds greeting history table for dynamic welcome messages. */
        private val MIGRATION_22_23 =
            object : Migration(22, 23) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `greeting_history` (
                            `id` TEXT NOT NULL,
                            `userId` TEXT NOT NULL,
                            `period` TEXT NOT NULL,
                            `date` TEXT NOT NULL,
                            `greeting` TEXT NOT NULL,
                            `generatedAt` INTEGER NOT NULL,
                            `source` TEXT NOT NULL,
                            PRIMARY KEY(`id`),
                            UNIQUE(`userId`, `period`, `date`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_greeting_history_userId_period_date` ON `greeting_history` (`userId`, `period`, `date`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_greeting_history_date` ON `greeting_history` (`date`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_greeting_history_userId` ON `greeting_history` (`userId`)",
                    )
                }
            }

        /** Migration from schema version 23 to 24: ensures the composite unique index on greeting_history exists. */
        private val MIGRATION_23_24 =
            object : Migration(23, 24) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_greeting_history_userId_period_date` ON `greeting_history` (`userId`, `period`, `date`)",
                    )
                }
            }

        /**
         * Removes the deprecated Vision, Transcription, and Query Classification
         * plugin rows. These tools were removed from the app UI and config layer;
         * deleting their rows here keeps existing installs in sync with [SeedData].
         */
        private val MIGRATION_24_25 =
            object : Migration(24, 25) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "DELETE FROM plugins WHERE id IN " +
                            "('official-vision', 'official-transcription', 'official-query-classification')",
                    )
                }
            }

        val MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
                MIGRATION_20_21,
                MIGRATION_21_22,
                MIGRATION_22_23,
                MIGRATION_23_24,
                MIGRATION_24_25,
            )

        /**
         * Builds a [ZeroClawDatabase] instance with seed data inserted on first creation.
         *
         * Applies all registered [MIGRATIONS] first, then falls back to destructive
         * migration as a development safety net for unhandled version jumps.
         *
         * If the database file is corrupted or encrypted with a different passphrase
         * (resulting in "file is not a database" error), the file is deleted and
         * recreated with the current passphrase.
         *
         * @param context Application context for database file location.
         * @param scope Coroutine scope for seed data insertion.
         * @return Configured [ZeroClawDatabase] instance.
         */
        fun build(
            context: Context,
            scope: CoroutineScope,
        ): ZeroClawDatabase {
            var instance: ZeroClawDatabase? = null
            val passphrase =
                AppStartupTrace.section("database_passphrase_load") {
                    DatabasePassphrase.getOrCreate(context)
                }
            AppStartupTrace.section("database_encryption_migration") {
                DatabaseEncryptionMigrator.migrateIfNeeded(context, passphrase)
            }
            val factory =
                AppStartupTrace.section("database_open_helper_factory") {
                    createOpenHelperFactory(passphrase)
                }
            val queryExecutor = Executors.newFixedThreadPool(DATABASE_QUERY_THREADS)
            val transactionExecutor = Executors.newSingleThreadExecutor()
            val db =
                AppStartupTrace.section("database_room_builder") {
                    buildDatabaseWithRetry(
                        context = context,
                        factory = factory,
                        queryExecutor = queryExecutor,
                        transactionExecutor = transactionExecutor,
                        scope = scope,
                        instanceRef = { instance = it },
                    )
                }
            instance = db
            return db
        }

        private fun buildDatabaseWithRetry(
            context: Context,
            factory: SupportOpenHelperFactory,
            queryExecutor: java.util.concurrent.Executor,
            transactionExecutor: java.util.concurrent.Executor,
            scope: CoroutineScope,
            instanceRef: (ZeroClawDatabase) -> Unit,
        ): ZeroClawDatabase {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            var attempt = 0
            while (true) {
                try {
                    return Room
                        .databaseBuilder(
                            context.applicationContext,
                            ZeroClawDatabase::class.java,
                            DATABASE_NAME,
                        ).openHelperFactory(factory)
                        .setQueryExecutor(queryExecutor)
                        .setTransactionExecutor(transactionExecutor)
                        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                        .apply { MIGRATIONS.forEach { addMigrations(it) } }
                        .fallbackToDestructiveMigration()
                        .addCallback(
                            object : Callback() {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    super.onCreate(db)
                                    scope.launch {
                                        AppStartupTrace.suspendSection("database_seed_plugins") {
                                            // Wait for instance to be set via instanceRef
                                            // The instance is assigned after build() returns
                                        }
                                    }
                                }
                            },
                        ).build()
                } catch (e: SQLiteException) {
                    attempt++
                    if (attempt > 2 || !isCorruptedDatabaseError(e)) {
                        Log.e(TAG, "Database build failed after $attempt attempt(s)", e)
                        throw e
                    }
                    Log.w(TAG, "Database corrupted or wrong passphrase (attempt $attempt), deleting and retrying: ${e.message}")
                    deleteDatabaseFiles(dbFile)
                    // Small delay before retry
                    Thread.sleep(100)
                }
            }
        }

        private fun isCorruptedDatabaseError(e: SQLiteException): Boolean {
            val msg = e.message?.lowercase() ?: return false
            return msg.contains("file is not a database") ||
                msg.contains("not a database") ||
                msg.contains("database disk image is malformed") ||
                msg.contains("hmac check failed") ||
                msg.contains("cipher") ||
                msg.contains("code 26") // SQLITE_NOTADB
        }

        private fun deleteDatabaseFiles(dbFile: java.io.File) {
            dbFile.delete()
            java.io.File(dbFile.absolutePath + "-wal").delete()
            java.io.File(dbFile.absolutePath + "-shm").delete()
            java.io.File(dbFile.absolutePath + "-journal").delete()
        }

        private fun createOpenHelperFactory(passphrase: String): SupportOpenHelperFactory {
            val hook = object : SQLiteDatabaseHook {
                override fun preKey(connection: SQLiteConnection) = Unit

                override fun postKey(connection: SQLiteConnection) {
                    runCatching {
                        connection.execute(
                            "PRAGMA cipher_memory_security = OFF",
                            null,
                            null,
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Failed to disable SQLCipher memory pinning", error)
                    }
                }
            }

            return SupportOpenHelperFactory(
                passphrase.toByteArray(Charsets.UTF_8),
                hook,
                true,
            )
        }

        /** Closes the database instance if it is open. */
        fun close(instance: ZeroClawDatabase?) {
            instance?.let {
                if (it.isOpen) {
                    it.close()
                }
            }
        }

        private const val TAG = "ZeroClawDatabase"
        private const val DATABASE_QUERY_THREADS = 1
    }
}
