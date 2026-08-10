/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.verify

import com.zeroclaw.android.capability.CapabilityRegistry
import com.zeroclaw.android.capability.CapabilityResolver
import com.zeroclaw.android.capability.ResolutionResult
import com.zeroclaw.android.diagnostics.RichRuntimeDiagnostics
import com.zeroclaw.android.goal.VerificationMode
import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.graph.TaskNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Result of verifying a task's outcome.
 */
sealed interface VerificationResult {
    data class Verified(
        val evidence: String? = null,
        val attempts: Int = 1,
    ) : VerificationResult

    data class NotVerified(
        val reason: String,
        val attempts: Int = 1,
    ) : VerificationResult
}

/**
 * A verifier checks that a completed task actually produced its intended world
 * state — e.g. the screenshot shows the setting, the repository exists, the
 * calendar event was created.
 */
interface TaskVerifier {
    val verifierId: String

    /**
     * Verifies the outcome of [task]. Implementations must be non-blocking and
     * honor their own timeouts.
     */
    suspend fun verify(task: TaskNode, outcome: ExecutionOutcome): VerificationResult
}

/**
 * Registered verifier implementations. The engine picks the verifier named by the
 * task's [com.zeroclaw.android.goal.graph.VerificationSpec], falling back to the
 * mode-appropriate default.
 */
class VerificationEngine {
    private val verifiers = mutableMapOf<String, TaskVerifier>()

    init {
        register(AlwaysPassVerifier())
        register(OutputSchemaVerifier())
    }

    fun register(verifier: TaskVerifier) {
        verifiers[verifier.verifierId] = verifier
    }

    fun getVerifier(verifierId: String): TaskVerifier? = verifiers[verifierId]

    /**
     * Verifies [outcome] against [task]'s verification spec.
     */
    suspend fun verify(task: TaskNode, outcome: ExecutionOutcome): VerificationResult {
        val spec = task.verification
        if (spec.mode == VerificationMode.NONE) {
            return VerificationResult.Verified(evidence = "verification disabled")
        }

        val verifier = spec.verifierId?.let { verifiers[it] } ?: defaultVerifier(spec.mode)
        var attempts = 0
        var last: VerificationResult = VerificationResult.NotVerified("no verifier", attempts)

        while (attempts < spec.maxAttempts.coerceAtLeast(1)) {
            attempts += 1
            last = runCatching {
                verifier.verify(task, outcome)
            }.getOrElse { error ->
                VerificationResult.NotVerified(
                    reason = error.message ?: "verifier threw",
                    attempts = attempts,
                )
            }
            if (last is VerificationResult.Verified) {
                RichRuntimeDiagnostics.record("GOAL_VERIFY", "Task ${task.taskId} verified by ${verifier.verifierId}")
                return last.copy(attempts = attempts)
            }
        }
        RichRuntimeDiagnostics.record(
            "GOAL_VERIFY_FAIL",
            "Task ${task.taskId} not verified by ${verifier.verifierId}: ${(last as VerificationResult.NotVerified).reason}",
        )
        return (last as VerificationResult.NotVerified).copy(attempts = attempts)
    }

    private fun defaultVerifier(mode: VerificationMode): TaskVerifier {
        return when (mode) {
            VerificationMode.NONE -> verifiers.getValue("ALWAYS_PASS")
            VerificationMode.BASIC -> verifiers.getValue("OUTPUT_SCHEMA")
            VerificationMode.STRICT -> verifiers.getValue("OUTPUT_SCHEMA")
        }
    }

    companion object {
        const val VERIFIER_ALWAYS_PASS = "ALWAYS_PASS"
        const val VERIFIER_OUTPUT_SCHEMA = "OUTPUT_SCHEMA"
    }
}

/**
 * Trusts the execution outcome unconditionally.
 */
class AlwaysPassVerifier : TaskVerifier {
    override val verifierId: String = VerificationEngine.VERIFIER_ALWAYS_PASS

    override suspend fun verify(task: TaskNode, outcome: ExecutionOutcome): VerificationResult {
        return VerificationResult.Verified(evidence = "trusted outcome")
    }
}

/**
 * Structural verifier: the output must parse as JSON and contain every key
 * listed in [com.zeroclaw.android.goal.graph.VerificationSpec.requiredOutputKeys]
 * (or, when no keys are listed, must be non-empty JSON or text).
 *
 * Maps to the "Repository Exists / Event Created" family of checks when a
 * capability-backed provider embeds existence flags in its JSON output.
 */
class OutputSchemaVerifier : TaskVerifier {
    override val verifierId: String = VerificationEngine.VERIFIER_OUTPUT_SCHEMA

    override suspend fun verify(task: TaskNode, outcome: ExecutionOutcome): VerificationResult {
        val output: String = when (outcome) {
            is ExecutionOutcome.Output -> outcome.outputJson
            else -> return VerificationResult.NotVerified("execution did not produce output")
        }

        val requiredKeys = task.verification.requiredOutputKeys
        if (requiredKeys.isEmpty()) {
            val trimmed = output.trim()
            return if (trimmed.isNotEmpty() && trimmed != "{}" && trimmed != "[]") {
                VerificationResult.Verified(evidence = "non-empty output")
            } else {
                VerificationResult.NotVerified("output was empty")
            }
        }

        val parsed = runCatching { Json.parseToJsonElement(output).jsonObject }.getOrNull()
            ?: return VerificationResult.NotVerified("output is not a JSON object")
        val missing = requiredKeys.filter { key -> !parsed.containsKey(key) }
        return if (missing.isEmpty()) {
            VerificationResult.Verified(evidence = "required keys present: ${requiredKeys.joinToString(",")}")
        } else {
            VerificationResult.NotVerified("missing output keys: ${missing.joinToString(",")}")
        }
    }
}

/**
 * Capability-backed verifier: re-checks world state by executing a verification
 * capability (e.g. "STATISTICS", "GITHUB_STATE", "CALENDAR_LOOKUP") and applying
 * a [StateCheck] predicate to its output. This is the generic engine for checks
 * like "Screenshot → Vision Check → Verified".
 */
class CapabilityVerifier(
    override val verifierId: String,
    private val verifyCapabilityId: String,
    private val stateCheck: StateCheck = StateCheck.NonEmpty,
) : TaskVerifier {

    override suspend fun verify(task: TaskNode, outcome: ExecutionOutcome): VerificationResult {
        val parameters = buildParameters(task, outcome)
        val result = CapabilityResolver.resolveAndExecute(
            capabilityId = verifyCapabilityId,
            parametersJson = parameters.toString(),
        )
        return when (result) {
            is ResolutionResult.Success -> {
                val parsed = runCatching { Json.parseToJsonElement(result.outputJson).jsonObject }.getOrNull()
                if (stateCheck.matches(parsed, result.outputJson)) {
                    VerificationResult.Verified(evidence = "state check passed via $verifyCapabilityId")
                } else {
                    VerificationResult.NotVerified("state check failed via $verifyCapabilityId")
                }
            }
            is ResolutionResult.Failure ->
                VerificationResult.NotVerified("verification capability failed: ${result.reason}")
        }
    }

    private fun buildParameters(task: TaskNode, outcome: ExecutionOutcome): JsonObject {
        val builder = kotlinx.serialization.json.buildJsonObject {
            put("taskId", task.taskId)
            put("capabilityId", task.capabilityId)
            val provider = (outcome as? ExecutionOutcome.Output)?.providerId
            if (provider != null) put("providerId", provider)
        }
        return builder
    }
}

/**
 * Predicate used by [CapabilityVerifier] against the verification capability output.
 */
fun interface StateCheck {
    fun matches(parsed: JsonObject?, rawJson: String): Boolean

    companion object {
        val NonEmpty: StateCheck = StateCheck { parsed, raw ->
            parsed != null && raw.isNotBlank()
        }

        val HasResults: StateCheck = StateCheck { parsed, _ ->
            parsed?.containsKey("results") == true
        }

        fun keyEquals(key: String, expected: String): StateCheck =
            StateCheck { parsed, _ ->
                parsed?.get(key)?.jsonPrimitive?.content == expected
            }

        /** Verifies a capability is available for a task (registry-level check). */
        val CapabilityRegistered: StateCheck = StateCheck { _, raw ->
            raw.contains("\"registered\":true")
        }
    }
}

/** Registry of verifiers commonly registered at app startup. */
object DefaultVerifiers {
    fun registerAll(engine: VerificationEngine) {
        engine.register(AlwaysPassVerifier())
        engine.register(OutputSchemaVerifier())
        // Optional capability-backed verifiers, registered lazily per capability.
        val registry = CapabilityRegistry
        // GITHUB: verify a repository exists after creation.
        if (registry.getCapability("GITHUB") != null && registry.getCapability("GITHUB_STATE") != null) {
            engine.register(CapabilityVerifier("REPOSITORY_EXISTS", "GITHUB_STATE", StateCheck.HasResults))
        }
        // CALENDAR: verify an event was created.
        if (registry.getCapability("CALENDAR_STATE") != null) {
            engine.register(CapabilityVerifier("EVENT_CREATED", "CALENDAR_STATE", StateCheck.HasResults))
        }
        // DEVICE_CONTROL + vision: verify a device action through a screenshot check.
        if (registry.getCapability("SCREENSHOT") != null && registry.getCapability("VISION_CHECK") != null) {
            engine.register(CapabilityVerifier("SCREENSHOT_VISION", "VISION_CHECK", StateCheck.HasResults))
        }
    }
}