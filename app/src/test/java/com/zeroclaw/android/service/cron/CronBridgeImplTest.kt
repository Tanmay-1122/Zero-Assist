/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

import com.zeroclaw.android.model.CronJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CronBridgeImplTest {
    @Test
    fun `creates recurring jobs through daemon client`() =
        runTest {
            val daemonClient = RecordingCronDaemonClient()
            val bridge = CronBridgeImpl(daemonClient)

            val jobId = bridge.addCronJob("*/5 * * * *", "sync")

            assertEquals("job-1", jobId)
            assertEquals(listOf("addJob:*/5 * * * *:sync"), daemonClient.calls)
        }

    @Test
    fun `creates interval jobs through daemon client`() =
        runTest {
            val daemonClient = RecordingCronDaemonClient()
            val bridge = CronBridgeImpl(daemonClient)

            val jobId = bridge.addInterval(60_000L, "sync")

            assertEquals("job-1", jobId)
            assertEquals(listOf("addJobEvery:60000:sync"), daemonClient.calls)
        }

    @Test
    fun `rejects non positive intervals`() =
        runTest {
            val bridge = CronBridgeImpl(RecordingCronDaemonClient())

            val error = runCatching { bridge.addInterval(0L, "sync") }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
        }

    @Test
    fun `maps daemon jobs to scheduler job info`() =
        runTest {
            val daemonClient =
                RecordingCronDaemonClient(
                    jobs =
                        mutableListOf(
                            cronJob(
                                id = "job-1",
                                expression = "*/5 * * * *",
                                command = "sync",
                                paused = true,
                                oneShot = false,
                            ),
                            cronJob(
                                id = "job-2",
                                expression = "10m",
                                command = "once",
                                paused = false,
                                oneShot = true,
                            ),
                        ),
                )
            val bridge = CronBridgeImpl(daemonClient)

            val jobs = bridge.listJobs()

            assertEquals(2, jobs.size)
            assertEquals("shell", jobs[0].jobType)
            assertFalse(jobs[0].enabled)
            assertEquals("oneshot", jobs[1].jobType)
            assertTrue(jobs[1].enabled)
        }

    @Test
    fun `updates enabled state through pause and resume only`() =
        runTest {
            val daemonClient = RecordingCronDaemonClient()
            val bridge = CronBridgeImpl(daemonClient)

            assertTrue(bridge.updateJob("job-1", enabled = false))
            assertTrue(bridge.updateJob("job-1", enabled = true))
            assertFalse(bridge.updateJob("job-1", expression = "* * * * *"))

            assertEquals(
                listOf(
                    "pauseJob:job-1",
                    "resumeJob:job-1",
                ),
                daemonClient.calls,
            )
        }

    private class RecordingCronDaemonClient(
        private val jobs: MutableList<CronJob> = mutableListOf(),
    ) : CronDaemonClient {
        val calls = mutableListOf<String>()

        override suspend fun listJobs(): List<CronJob> = jobs

        override suspend fun getJob(id: String): CronJob? = jobs.firstOrNull { job -> job.id == id }

        override suspend fun addJob(
            expression: String,
            command: String,
        ): CronJob {
            calls += "addJob:$expression:$command"
            return cronJob(id = "job-${calls.size}", expression = expression, command = command)
        }

        override suspend fun addOneShot(
            delay: String,
            command: String,
        ): CronJob {
            calls += "addOneShot:$delay:$command"
            return cronJob(id = "job-${calls.size}", expression = delay, command = command, oneShot = true)
        }

        override suspend fun addJobEvery(
            intervalMs: ULong,
            command: String,
        ): CronJob {
            calls += "addJobEvery:$intervalMs:$command"
            return cronJob(id = "job-${calls.size}", expression = intervalMs.toString(), command = command)
        }

        override suspend fun removeJob(id: String): Boolean {
            calls += "removeJob:$id"
            return true
        }

        override suspend fun pauseJob(id: String): Boolean {
            calls += "pauseJob:$id"
            return true
        }

        override suspend fun resumeJob(id: String): Boolean {
            calls += "resumeJob:$id"
            return true
        }
    }

    private companion object {
        fun cronJob(
            id: String,
            expression: String = "* * * * *",
            command: String = "echo ok",
            paused: Boolean = false,
            oneShot: Boolean = false,
        ): CronJob =
            CronJob(
                id = id,
                expression = expression,
                command = command,
                nextRunMs = 10L,
                lastRunMs = null,
                lastStatus = null,
                paused = paused,
                oneShot = oneShot,
            )
    }
}
