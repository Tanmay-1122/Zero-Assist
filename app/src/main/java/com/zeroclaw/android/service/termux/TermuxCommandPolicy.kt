/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class TermuxCommandRisk {
    LOW,
    MEDIUM,
    HIGH,
    BLOCKED,
}

data class TermuxCommandPolicyInput(
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val prootDistro: String? = null,
)

data class TermuxCommandPolicyDecision(
    val risk: TermuxCommandRisk,
    val reason: String,
)

data class TermuxApprovalRequest(
    val id: String,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val risk: TermuxCommandRisk,
    val reason: String,
    val fingerprint: String,
    val blocked: Boolean = false,
) {
    val argv: List<String>
        get() = listOf(command) + arguments

    val commandPreview: String
        get() =
            TermuxCommandPolicyInput(
                command = command,
                arguments = arguments,
                workingDirectory = workingDirectory,
            ).toCommandPreview()

    companion object {
        fun fromToolOutput(
            toolName: String,
            output: String,
        ): TermuxApprovalRequest? {
            if (toolName != "termux_run") return null
            val root = runCatching { JSONObject(output) }.getOrNull() ?: return null
            val approvalRequired = root.optBoolean("approval_required", false)
            val blocked = root.optBoolean("blocked", false)
            if (!approvalRequired && !blocked) return null

            val command = root.optString("command").trim().takeIf { it.isNotBlank() } ?: return null
            val arguments = root.optJSONArray("arguments").toStringList()
            val workingDirectory =
                root.optString("working_directory")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: DEFAULT_TERMUX_WORKSPACE
            val risk =
                runCatching { TermuxCommandRisk.valueOf(root.optString("risk").uppercase()) }
                    .getOrDefault(if (blocked) TermuxCommandRisk.HIGH else TermuxCommandRisk.MEDIUM)
                    .let { parsedRisk ->
                        if (parsedRisk == TermuxCommandRisk.BLOCKED) {
                            TermuxCommandRisk.HIGH
                        } else {
                            parsedRisk
                        }
                    }

            return TermuxApprovalRequest(
                id =
                    root.optString("request_id")
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?: TermuxCommandPolicyInput(command, arguments, workingDirectory).approvalFingerprint(),
                command = command,
                arguments = arguments,
                workingDirectory = workingDirectory,
                risk = risk,
                reason = root.optString("reason").ifBlank { "Termux command needs approval." },
                fingerprint =
                    root.optString("fingerprint")
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?: TermuxCommandPolicyInput(command, arguments, workingDirectory).approvalFingerprint(),
                blocked = false,
            )
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
}

class TermuxCommandPolicy {
    fun classify(input: TermuxCommandPolicyInput): TermuxCommandPolicyDecision {
        val command = input.command.trim()
        if (command.isBlank()) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.BLOCKED,
                reason = "Command path or name is required.",
            )
        }

        val executable = command.substringAfterLast('/').lowercase()
        val normalizedArguments = input.arguments.map { it.trim() }

        findBlockedToken(executable, normalizedArguments)?.let { token ->
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.HIGH,
                reason = "Interactive shells, privilege escalation, package chroots, and command chaining need explicit user approval: $token.",
            )
        }

        if (hasUnsafeWorkingDirectory(input.workingDirectory)) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.HIGH,
                reason = "Working directory is outside the standard Termux app home or usr directories and needs explicit user approval.",
            )
        }

        if (isBridgeBootstrap(executable, normalizedArguments)) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.LOW,
                reason = "Zero-Assist Termux bridge bootstrap request targets the reserved bridge script.",
            )
        }

        if (executable in highRiskCommands || normalizedArguments.any { it in highRiskArguments }) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.HIGH,
                reason = "Command can alter files, processes, packages, networking, or device state.",
            )
        }

        if (!input.prootDistro.isNullOrBlank()) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.MEDIUM,
                reason = "Command is scoped to a proot distro and needs explicit review before execution support is added.",
            )
        }

        if (executable == "touch" && normalizedArguments.areLowRiskTouchArguments()) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.LOW,
                reason = "Command creates or updates simple files inside the Termux workspace.",
            )
        }

        if (executable in lowRiskCommands && normalizedArguments.none { it.startsWith("-c") }) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.LOW,
                reason = "Command is a bounded diagnostic or read-only inspection command.",
            )
        }

        return TermuxCommandPolicyDecision(
            risk = TermuxCommandRisk.MEDIUM,
            reason = "Command is not on the low-risk diagnostic list and needs approval before future execution.",
        )
    }

    /**
     * Classifies a command and applies a permission tier to produce a user-facing decision.
     *
     * Tier behavior:
     * - **MEDIUM**: LOW auto-allowed, MEDIUM needs approval, HIGH blocked.
     * - **HIGH**: LOW+MEDIUM+HIGH auto-allowed, BLOCKED needs approval.
     * - **UNCONSTRAINED**: everything allowed.
     */
    fun classifyWithTier(
        input: TermuxCommandPolicyInput,
        tier: TermuxPermissionTier,
        preApprovedPatterns: List<String> = emptyList(),
    ): TermuxCommandPolicyDecision {
        if (tier == TermuxPermissionTier.UNCONSTRAINED) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.LOW,
                reason = "Termux is in unconstrained mode — all commands are allowed.",
            )
        }

        if (matchesPreApprovedPattern(input, preApprovedPatterns)) {
            return TermuxCommandPolicyDecision(
                risk = TermuxCommandRisk.LOW,
                reason = "Command matches a pre-approved pattern.",
            )
        }

        val base = classify(input)

        return when (tier) {
            TermuxPermissionTier.MEDIUM -> base
            TermuxPermissionTier.HIGH ->
                when (base.risk) {
                    TermuxCommandRisk.LOW,
                    TermuxCommandRisk.MEDIUM,
                    TermuxCommandRisk.HIGH,
                    -> TermuxCommandPolicyDecision(risk = TermuxCommandRisk.LOW, reason = "Auto-allowed by high permission tier.")
                    TermuxCommandRisk.BLOCKED -> base
                }
            TermuxPermissionTier.UNCONSTRAINED -> TermuxCommandPolicyDecision(risk = TermuxCommandRisk.LOW, reason = "Unconstrained mode.")
        }
    }

    private fun matchesPreApprovedPattern(
        input: TermuxCommandPolicyInput,
        patterns: List<String>,
    ): Boolean {
        if (patterns.isEmpty()) return false
        val argv = listOf(input.command.substringAfterLast('/')) + input.arguments
        val argvString = argv.joinToString(" ")
        return patterns.any { pattern ->
            val trimmed = pattern.trim()
            if (trimmed.endsWith("*")) {
                val prefix = trimmed.removeSuffix("*").trimEnd()
                argvString.startsWith(prefix)
            } else {
                argvString == trimmed || argvString.startsWith("$trimmed ")
            }
        }
    }

    private fun findBlockedToken(
        executable: String,
        arguments: List<String>,
    ): String? {
        if (executable in blockedCommands) {
            return executable
        }

        return arguments.firstOrNull { argument ->
            blockedArgumentFragments.any { fragment -> argument.contains(fragment) }
        }
    }

    private fun hasUnsafeWorkingDirectory(workingDirectory: String?): Boolean {
        val directory = workingDirectory?.trim().orEmpty()
        if (directory.isBlank()) {
            return false
        }

        return !directory.isAtOrInside(TERMUX_HOME_PREFIX) &&
            !directory.isAtOrInside(TERMUX_USR_PREFIX)
    }

    private fun String.isAtOrInside(prefix: String): Boolean =
        this == prefix || startsWith("$prefix/")

    private fun isBridgeBootstrap(
        executable: String,
        arguments: List<String>,
    ): Boolean =
        executable in pythonCommands &&
            arguments.firstOrNull() == TermuxBridgeBootstrapRequestBuilder.DEFAULT_BRIDGE_SCRIPT_PATH &&
            "--port" in arguments &&
            "--token" in arguments

    private fun List<String>.areLowRiskTouchArguments(): Boolean =
        isNotEmpty() &&
            all { argument ->
                argument.isNotBlank() &&
                    !argument.startsWith("-") &&
                    !argument.startsWith("/") &&
                    !argument.contains("..") &&
                    !argument.contains("\\")
            }

    private companion object {
        private const val TERMUX_HOME_PREFIX = "/data/data/com.termux/files/home"
        private const val TERMUX_USR_PREFIX = "/data/data/com.termux/files/usr"

        private val pythonCommands = setOf("python", "python3")

        private val blockedCommands =
            setOf(
                "bash",
                "fish",
                "login",
                "proot",
                "sh",
                "su",
                "termux-chroot",
                "tsu",
                "zsh",
            )

        private val blockedArgumentFragments =
            setOf(
                "&&",
                "||",
                ";",
                "`",
                "$(",
                "| sh",
                "| bash",
                ">/",
                "</",
            )

        private val highRiskCommands =
            setOf(
                "apt",
                "chmod",
                "chown",
                "curl",
                "dd",
                "git",
                "kill",
                "ln",
                "mkfs",
                "mv",
                "nano",
                "nc",
                "node",
                "npm",
                "pkg",
                "python",
                "python3",
                "rm",
                "ssh",
                "tar",
                "termux-open",
                "wget",
            )

        private val highRiskArguments =
            setOf(
                "--force",
                "-f",
                "-rf",
                "-fr",
                "--recursive",
            )

        private val lowRiskCommands =
            setOf(
                "date",
                "false",
                "id",
                "pwd",
                "true",
                "uname",
                "whoami",
            )
    }
}

fun TermuxCommandPolicyInput.normalizedWorkingDirectory(): String =
    workingDirectory
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_TERMUX_WORKSPACE

fun TermuxCommandPolicyInput.normalizedArgv(): List<String> =
    buildList {
        command.trim().takeIf { it.isNotBlank() }?.let(::add)
        arguments.mapNotNullTo(this) { argument -> argument.trim().takeIf { it.isNotBlank() } }
    }

fun TermuxCommandPolicyInput.approvalFingerprint(): String {
    val canonical =
        buildString {
            append("termux-v1\nargv=")
            append(JSONArray(normalizedArgv()).toString())
            append("\nworking_directory=")
            append(JSONObject.quote(normalizedWorkingDirectory()))
        }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

const val DEFAULT_TERMUX_WORKSPACE = "/data/data/com.termux/files/home/.zero-assist/workspace"
