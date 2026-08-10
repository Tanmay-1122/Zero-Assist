/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.graph.TaskSecurity
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.goal.schedule.ResourceLimits
import com.zeroclaw.android.goal.verify.VerificationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class GoalSecurityTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    @Test
    fun `read only grant covers network tasks`() {
        val enforcer = GoalSecurityEnforcer()
        val task = TaskNode(taskId = "t", capabilityId = "X", security = TaskSecurity(SecurityContext(network = true)))
        assertTrue(enforcer.authorize(task, GoalSecurityEnforcer.READ_ONLY))
    }

    @Test
    fun `read only grant denies device control`() {
        val enforcer = GoalSecurityEnforcer()
        val task = TaskNode(taskId = "t", capabilityId = "X", security = TaskSecurity(SecurityContext(deviceControl = true)))
        assertFalse(enforcer.authorize(task, GoalSecurityEnforcer.READ_ONLY))
        assertEquals(listOf("DEVICE_CONTROL"), enforcer.deniedReasons(task, GoalSecurityEnforcer.READ_ONLY))
    }

    @Test
    fun `effective grant unions goal and identity contexts`() {
        val enforcer = GoalSecurityEnforcer()
        val goal = testGoal("g", securityContext = SecurityContext(network = true, filesystem = true))
        val grant = enforcer.effectiveGrant(goal, GoalSecurityEnforcer.READ_ONLY)
        assertTrue(grant.network)
        assertTrue(grant.filesystem)
        assertFalse(grant.deviceControl)
    }

    @Test
    fun `full access covers every permission`() {
        val enforcer = GoalSecurityEnforcer()
        val task = TaskNode(
            taskId = "t",
            capabilityId = "X",
            security = TaskSecurity(
                SecurityContext(
                    network = true,
                    filesystem = true,
                    deviceControl = true,
                    mcpAccess = true,
                    sandboxAccess = true,
                ),
            ),
        )
        assertTrue(enforcer.authorize(task, GoalSecurityEnforcer.FULL_ACCESS))
    }

    @Test
    fun `scheduler blocks unauthorized tasks without executing them`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("DEVICE") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        val scheduler = GoalScheduler(
            executor = executor,
            verificationEngine = VerificationEngine(),
            recoveryEngine = com.zeroclaw.android.goal.recover.RecoveryEngine(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            resourceLimits = ResourceLimits(maxConcurrentTasks = 2),
            identityGrant = GoalSecurityEnforcer.READ_ONLY,
        )
        val graph = TaskGraph.from(
            "secure",
            listOf(
                TaskNode(
                    taskId = "dev_task",
                    capabilityId = "DEVICE",
                    security = TaskSecurity(SecurityContext(deviceControl = true)),
                ),
            ),
        )
        val goal = testGoal("secure")

        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.FAILED, result.status)
        assertEquals(TaskState.FAILED, result.graph.node("dev_task")!!.state)
        assertEquals(0, executor.countFor("DEVICE"))
    }
}
