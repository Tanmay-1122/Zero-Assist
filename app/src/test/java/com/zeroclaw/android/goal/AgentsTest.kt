/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.goal.agents.AgentCoordinator
import com.zeroclaw.android.goal.agents.AgentOutputMerger
import com.zeroclaw.android.goal.agents.AgentRole
import com.zeroclaw.android.goal.agents.AgentOutput
import com.zeroclaw.android.goal.agents.CapabilitySubAgent
import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.graph.MergePolicy
import com.zeroclaw.android.goal.graph.TaskNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class AgentsTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    @Test
    fun `capability sub agent runs a task through the executor`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("SEARCH") { _ -> ExecutionOutcome.Output("p1", "{\"hits\":3}") }
        val agent = CapabilitySubAgent(agentId = "researcher", role = AgentRole.RESEARCH, executor = executor)
        val task = TaskNode(taskId = "t", capabilityId = "SEARCH")

        val output = agent.run(task, CancellationToken())

        assertTrue(output.success)
        assertEquals("t", output.taskId)
        assertEquals("researcher", output.agentId)
        assertTrue(output.outputJson.contains("\"hits\":3"))
    }

    @Test
    fun `sub agent reports executor failures`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("SEARCH") { _ -> ExecutionOutcome.Failure("down") }
        val agent = CapabilitySubAgent(agentId = "researcher", role = AgentRole.RESEARCH, executor = executor)

        val output = agent.run(TaskNode(taskId = "t", capabilityId = "SEARCH"), CancellationToken())

        assertFalse(output.success)
        assertEquals("down", output.error)
    }

    @Test
    fun `coordinator resolves by explicit agent id`() {
        val generalist = CapabilitySubAgent("g", AgentRole.GENERALIST, ScriptedExecutor())
        val researcher = CapabilitySubAgent("r", AgentRole.RESEARCH, ScriptedExecutor(), supportedCapabilities = setOf("SEARCH"))
        val coordinator = AgentCoordinator(listOf(generalist, researcher))

        val explicit = coordinator.resolveAgent(TaskNode(taskId = "t", capabilityId = "SEARCH", agentId = "r"))
        assertEquals("r", explicit?.agentId)
    }

    @Test
    fun `coordinator resolves by supported capability`() {
        val researcher = CapabilitySubAgent("r", AgentRole.RESEARCH, ScriptedExecutor(), supportedCapabilities = setOf("SEARCH"))
        val coordinator = AgentCoordinator(listOf(researcher))

        val picked = coordinator.resolveAgent(TaskNode(taskId = "t", capabilityId = "SEARCH"))
        assertEquals("r", picked?.agentId)
    }

    @Test
    fun `coordinator falls back to generalist`() {
        val generalist = CapabilitySubAgent("g", AgentRole.GENERALIST, ScriptedExecutor())
        val coordinator = AgentCoordinator(listOf(generalist))

        val picked = coordinator.resolveAgent(TaskNode(taskId = "t", capabilityId = "UNKNOWN"))
        assertEquals("g", picked?.agentId)
    }

    @Test
    fun `coordinator returns null when nothing matches`() {
        val coordinator = AgentCoordinator(listOf(CapabilitySubAgent("r", AgentRole.RESEARCH, ScriptedExecutor())))
        assertNull(coordinator.resolveAgent(TaskNode(taskId = "t", capabilityId = "UNKNOWN")))
    }

    @Test
    fun `merge concat joins outputs`() {
        val merged = AgentOutputMerger.merge(
            listOf(
                AgentOutput("a", "t1", success = true, outputJson = "{\"a\":1}"),
                AgentOutput("b", "t2", success = true, outputJson = "{\"b\":2}"),
            ),
            MergePolicy.CONCAT,
        )
        assertTrue(merged.success)
        assertTrue(merged.outputJson.contains("{\"a\":1}"))
        assertTrue(merged.outputJson.contains("{\"b\":2}"))
    }

    @Test
    fun `merge json merges keys`() {
        val merged = AgentOutputMerger.merge(
            listOf(
                AgentOutput("a", "t1", success = true, outputJson = "{\"a\":1,\"shared\":\"x\"}"),
                AgentOutput("b", "t2", success = true, outputJson = "{\"b\":2,\"shared\":\"y\"}"),
            ),
            MergePolicy.JSON_MERGE,
        )
        assertTrue(merged.success)
        assertTrue(merged.outputJson.contains("\"a\""))
        assertTrue(merged.outputJson.contains("\"b\""))
    }

    @Test
    fun `merge first success picks first ok output`() {
        val merged = AgentOutputMerger.merge(
            listOf(
                AgentOutput("a", "t1", success = false, error = "boom"),
                AgentOutput("b", "t2", success = true, outputJson = "{\"ok\":true}"),
            ),
            MergePolicy.FIRST_SUCCESS,
        )
        assertNotNull(merged)
        assertEquals("b", merged.agentId)
        assertTrue(merged.success)
    }
}
