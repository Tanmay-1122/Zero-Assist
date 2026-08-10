/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.goal.agents.CapabilityTaskExecutor
import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.VerificationSpec
import com.zeroclaw.android.goal.plan.AlternativePlanProvider
import com.zeroclaw.android.goal.recover.HumanClarificationAnswer
import com.zeroclaw.android.goal.recover.ClarificationResponder
import com.zeroclaw.android.goal.recover.RecoveryEngine
import com.zeroclaw.android.goal.schedule.ExecutionEvent
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.goal.schedule.ResourceLimits
import com.zeroclaw.android.goal.verify.OutputSchemaVerifier
import com.zeroclaw.android.goal.verify.VerificationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class RecoveryTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    private fun buildScheduler(
        scope: CoroutineScope,
        executor: ScriptedExecutor,
        recovery: RecoveryEngine,
        verification: VerificationEngine = VerificationEngine(),
        replanProvider: AlternativePlanProvider? = null,
    ): GoalScheduler = GoalScheduler(
        executor = executor,
        verificationEngine = verification,
        recoveryEngine = recovery,
        replanProvider = replanProvider,
        scope = scope,
        resourceLimits = ResourceLimits(maxConcurrentTasks = 4),
        identityGrant = GoalSecurityEnforcer.READ_ONLY,
    )

    @Test
    fun `retry policy retries then succeeds`() = runTest {
        val executor = ScriptedExecutor()
        executor.failOnceThenSucceed("FLAPPY")
        val recovery = RecoveryEngine()
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, recovery)
        val graph = chainGraph("retry", listOf("FLAPPY"))

        val goal = testGoal("retry").copy(
            failurePolicy = FailurePolicy(actions = listOf(FailurePolicyAction.RETRY), maxRetries = 2, retryBackoffMs = 1),
        )
        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(2, executor.countFor("FLAPPY"))
        val node = result.graph.node("task_0")!!
        assertEquals(TaskState.SUCCEEDED, node.state)
        assertTrue(node.retryCount >= 1)
    }

    @Test
    fun `exhausted retries fail the task`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("DEAD") { _ -> ExecutionOutcome.Failure("always dies") }
        val recovery = RecoveryEngine()
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, recovery)
        val graph = chainGraph("dead", listOf("DEAD"))

        val goal = testGoal("dead").copy(
            failurePolicy = FailurePolicy(actions = listOf(FailurePolicyAction.RETRY), maxRetries = 1, retryBackoffMs = 1),
        )
        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.FAILED, result.status)
        assertEquals(2, executor.countFor("DEAD"))
    }

    @Test
    fun `fallback provider recovery skips failing provider`() = runTest {
        // Provider A (highest priority) produces unverifiable output; provider B works.
        registerTestCapability(
            "GOAEE_FB_CAP",
            provider("fb_a", "GOAEE_FB_CAP", 100, "{\"bad\":true}"),
            provider("fb_b", "GOAEE_FB_CAP", 50, "{\"ok\":true}"),
        )

        val engine = VerificationEngine()
        engine.register(OutputSchemaVerifier())
        val scheduler = GoalScheduler(
            executor = CapabilityTaskExecutor(),
            verificationEngine = engine,
            recoveryEngine = RecoveryEngine(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            resourceLimits = ResourceLimits(maxConcurrentTasks = 4),
            identityGrant = GoalSecurityEnforcer.READ_ONLY,
        )
        val graph = TaskGraph.from(
            "fb",
            listOf(
                TaskNode(
                    taskId = "t",
                    capabilityId = "GOAEE_FB_CAP",
                    verification = VerificationSpec(
                        verifierId = "OUTPUT_SCHEMA",
                        requiredOutputKeys = listOf("ok"),
                    ),
                    fallback = com.zeroclaw.android.goal.graph.FallbackSpec(maxProviderFallbacks = 2),
                ),
            ),
        )
        val goal = testGoal("fb").copy(
            failurePolicy = FailurePolicy(actions = listOf(FailurePolicyAction.FALLBACK_PROVIDER)),
        )

        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        val node = result.graph.node("t")!!
        assertEquals("fb_b", node.result!!.providerId)
        assertEquals(TaskState.SUCCEEDED, node.state)
    }

    @Test
    fun `alternative capability recovery switches capabilities`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("PRIMARY_CAP") { _ -> ExecutionOutcome.Failure("primary broken") }
        executor.on("ALT_CAP") { _ -> ExecutionOutcome.Output("alt_provider", "{\"ok\":true}") }
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, RecoveryEngine())
        val graph = TaskGraph.from(
            "altcap",
            listOf(
                TaskNode(
                    taskId = "t",
                    capabilityId = "PRIMARY_CAP",
                    fallback = com.zeroclaw.android.goal.graph.FallbackSpec(alternativeCapabilities = listOf("ALT_CAP")),
                ),
            ),
        )
        val goal = testGoal("altcap").copy(
            failurePolicy = FailurePolicy(actions = listOf(FailurePolicyAction.ALTERNATIVE_CAPABILITY)),
        )

        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        val node = result.graph.node("t")!!
        assertEquals("ALT_CAP", node.result!!.capabilityId)
        assertEquals(TaskState.SUCCEEDED, node.state)
    }

    @Test
    fun `replan inserts alternative airports and continues without restart`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("FLIGHTS_SEARCH") { _ -> ExecutionOutcome.Failure("No flights available") }
        executor.on("ALT_AIRPORTS_SEARCH") { _ -> ExecutionOutcome.Output("p", "{\"airports\":[\"YYZ\"]}") }
        executor.on("ALT_FLIGHTS") { _ -> ExecutionOutcome.Output("p", "{\"flights\":[{\"id\":1}]}") }
        executor.on("BOOKING") { _ -> ExecutionOutcome.Output("p", "{\"booked\":true}") }

        val replanProvider = AlternativePlanProvider { _, _, _ ->
            listOf(
                GoalPlanStep(capabilityId = "ALT_AIRPORTS_SEARCH", taskId = "alt_search"),
                GoalPlanStep(capabilityId = "ALT_FLIGHTS", taskId = "alt_flights", dependsOn = listOf("alt_search")),
            )
        }
        val scheduler = buildScheduler(
            CoroutineScope(coroutineContext + SupervisorJob()),
            executor,
            RecoveryEngine(replanProvider = replanProvider),
            replanProvider = replanProvider,
        )
        val graph = chainGraph("flights", listOf("FLIGHTS_SEARCH", "BOOKING"))
        val goal = testGoal("flights").copy(
            failurePolicy = FailurePolicy(actions = listOf(FailurePolicyAction.REPLAN)),
        )

        val replanEvents = mutableListOf<ExecutionEvent.Replanned>()
        val collector = CoroutineScope(coroutineContext + SupervisorJob()).launch {
            scheduler.events.collect { event ->
                if (event is ExecutionEvent.Replanned) replanEvents.add(event)
            }
        }

        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()
        collector.cancel()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(1, replanEvents.size)
        // The failed flight search was replaced mid-flight; execution continued.
        assertNull(result.graph.node("task_0"))
        assertEquals(TaskState.SUCCEEDED, result.graph.node("alt_search")!!.state)
        assertEquals(TaskState.SUCCEEDED, result.graph.node("alt_flights")!!.state)
        assertEquals(TaskState.SUCCEEDED, result.graph.node("task_1")!!.state)
        assertTrue(result.graph.revision > 1)
    }

    @Test
    fun `human clarification pauses and resumes on answer`() = runTest {
        val executor = ScriptedExecutor()
        executor.failOnceThenSucceed("CLARIFY")
        val recovery = RecoveryEngine(responder = ClarificationResponder { null })
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, recovery)
        val graph = chainGraph("clarify", listOf("CLARIFY"))
        val goal = testGoal("clarify").copy(
            failurePolicy = FailurePolicy(actions = listOf(FailurePolicyAction.HUMAN_CLARIFICATION)),
        )

        val requests = mutableListOf<ExecutionEvent.HumanClarificationRequested>()
        val collector = CoroutineScope(coroutineContext + SupervisorJob()).launch {
            scheduler.events.collect { event ->
                if (event is ExecutionEvent.HumanClarificationRequested) requests.add(event)
            }
        }

        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        assertTrue(requests.isNotEmpty())
        assertEquals(1, executor.countFor("CLARIFY"))

        scheduler.answerClarification(requests.first().request.requestId, HumanClarificationAnswer.Proceed())
        advanceUntilIdle()
        val result = deferred.await()
        collector.cancel()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(2, executor.countFor("CLARIFY"))
    }

    @Test
    fun `abort policy marks goal failed`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("ABORT_CAP") { _ -> ExecutionOutcome.Failure("nope") }
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, RecoveryEngine())
        val graph = chainGraph("abort", listOf("ABORT_CAP"))
        val goal = testGoal("abort").copy(
            failurePolicy = FailurePolicy(actions = listOf(FailurePolicyAction.ABORT)),
        )

        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.FAILED, result.status)
        assertEquals(TaskState.FAILED, result.graph.node("task_0")!!.state)
    }
}

