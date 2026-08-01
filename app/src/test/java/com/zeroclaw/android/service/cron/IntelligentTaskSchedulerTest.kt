/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import com.zeroclaw.android.service.ChannelDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntelligentTaskSchedulerTest {
    @Test
    fun `ignores messages that are not scheduling requests`() =
        runTest {
            val scheduler = scheduler(channels = emptyList())

            val result = scheduler.scheduleFromNaturalLanguage("what is the project status")

            assertEquals(ScheduleResult.NotSchedulingRequest, result)
        }

    @Test
    fun `returns no channel result when requested channel is unavailable`() =
        runTest {
            val scheduler = scheduler(channels = emptyList())

            val result = scheduler.scheduleFromNaturalLanguage("check server every 5 minutes")

            assertEquals(ScheduleResult.NoChannelsFound("telegram"), result)
        }

    @Test
    fun `creates recurring job when requested channel is available`() =
        runTest {
            val cronBridge = RecordingCronBridge()
            val scheduler =
                scheduler(
                    channels =
                        listOf(
                            ConnectedChannel(
                                id = "telegram-1",
                                type = ChannelType.TELEGRAM,
                                isEnabled = true,
                            ),
                        ),
                    cronBridge = cronBridge,
                )

            val result = scheduler.scheduleFromNaturalLanguage("check server every 5 minutes")

            assertTrue(result is ScheduleResult.Success)
            assertEquals("job-1", (result as ScheduleResult.Success).jobId)
            assertEquals(listOf("addCronJob:*/5 * * * *:check server every 5 minutes"), cronBridge.calls)
        }

    private fun scheduler(
        channels: List<ConnectedChannel>,
        cronBridge: RecordingCronBridge = RecordingCronBridge(),
    ): IntelligentTaskScheduler =
        IntelligentTaskScheduler(
            intentParser = CronIntentParser(),
            cronTranslator = CronTranslator(),
            channelDetector = ChannelDetector(FakeChannelConfigRepository(channels)),
            cronBridge = cronBridge,
        )

    private class FakeChannelConfigRepository(
        initialChannels: List<ConnectedChannel>,
    ) : ChannelConfigRepository {
        override val channels: Flow<List<ConnectedChannel>> = MutableStateFlow(initialChannels)

        override suspend fun getById(id: String): ConnectedChannel? = null

        override suspend fun getByType(type: ChannelType): ConnectedChannel? = null

        override suspend fun existsForType(type: ChannelType): Boolean = false

        override suspend fun save(
            channel: ConnectedChannel,
            secrets: Map<String, String>,
        ) = Unit

        override suspend fun delete(id: String) = Unit

        override suspend fun toggleEnabled(id: String) = Unit

        override suspend fun setEnabled(
            id: String,
            enabled: Boolean,
        ) = Unit

        override fun getSecrets(channelId: String): Map<String, String> = emptyMap()

        override suspend fun getEnabledWithSecrets(): List<Pair<ConnectedChannel, Map<String, String>>> = emptyList()
    }

    private class RecordingCronBridge : CronBridge {
        val calls = mutableListOf<String>()

        override suspend fun addCronJob(
            cronExpression: String,
            command: String,
            name: String,
        ): String {
            calls += "addCronJob:$cronExpression:$command"
            return "job-${calls.size}"
        }

        override suspend fun addOneShot(
            delay: String,
            command: String,
            name: String,
        ): String {
            calls += "addOneShot:$delay:$command"
            return "job-${calls.size}"
        }

        override suspend fun addInterval(
            intervalMs: Long,
            command: String,
            name: String,
        ): String {
            calls += "addInterval:$intervalMs:$command"
            return "job-${calls.size}"
        }

        override suspend fun addAgentJob(
            cronExpression: String,
            prompt: String,
            model: String,
            name: String,
        ): String {
            calls += "addAgentJob:$cronExpression:$prompt"
            return "job-${calls.size}"
        }

        override suspend fun listJobs(): List<CronJobInfo> = emptyList()

        override suspend fun getJob(jobId: String): CronJobInfo? = null

        override suspend fun updateJob(
            jobId: String,
            expression: String?,
            command: String?,
            enabled: Boolean?,
        ): Boolean = false

        override suspend fun removeJob(jobId: String): Boolean = false

        override suspend fun pauseJob(jobId: String): Boolean = false

        override suspend fun resumeJob(jobId: String): Boolean = false

        override suspend fun getJobRuns(
            jobId: String,
            limit: Int,
        ): List<JobRun> = emptyList()
    }
}
