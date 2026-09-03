/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import com.zeroclaw.android.BuildConfig

/** Parses broad user phone-control requests into UI-agent goals. */
object UiAgentGoalParser {
    fun parse(transcript: String): UiAgentGoal? {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return null

        return sendMessageGoal(trimmed)
            ?: messagePreparationGoal(trimmed)
            ?: openAppWorkflowGoal(trimmed)
            ?: directUiGoal(trimmed)
            ?: genericPhoneGoal(trimmed)
    }

    private fun sendMessageGoal(transcript: String): UiAgentGoal.SendMessage? {
        MESSAGE_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(transcript)
        }?.let { match ->
            val recipient = match.groupValues[1].cleanCapture()
            val appPreference = match.groupValues[2].extractTrailingAppPreference()
            val message = appPreference.cleanedText
            if (message.isBlank()) return null
            return UiAgentGoal.SendMessage(
                recipient = recipient.takeIf { it.isNotBlank() },
                message = message,
                targetPackageName = appPreference.packageName,
                targetAppQuery = appPreference.appQuery,
            )
        }

        return null
    }

    private fun messagePreparationGoal(transcript: String): UiAgentGoal.Generic? {
        BODYLESS_MESSAGE_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(transcript)?.groupValues?.get(1)?.cleanCapture()
        }?.takeIf { remainder -> remainder.isNotBlank() }?.let { remainder ->
            val appPreference = remainder.extractTrailingAppPreference()
            if (!appPreference.hasTarget || appPreference.cleanedText.isBlank()) return null
            return UiAgentGoal.Generic(
                instruction =
                    "open the target messaging app and find the chat or contact named " +
                        "${appPreference.cleanedText}; do not type or send a message because no message text was provided",
                targetPackageName = appPreference.packageName,
                targetAppQuery = appPreference.appQuery,
            )
        }

        return null
    }

    private fun openAppWorkflowGoal(transcript: String): UiAgentGoal.Generic? {
        val match = OPEN_APP_WORKFLOW_PATTERN.matchEntire(transcript) ?: return null
        val target = match.groupValues[1].toAppTarget()
        if (!target.hasTarget) return null
        return UiAgentGoal.Generic(
            instruction = transcript.cleanCapture(),
            targetPackageName = target.packageName,
            targetAppQuery = target.appQuery,
        )
    }

    private fun directUiGoal(transcript: String): UiAgentGoal.Generic? {
        DIRECT_UI_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(transcript)?.groupValues?.get(1)?.cleanCapture()
        }?.takeIf { instruction -> instruction.isNotBlank() }?.let { instruction ->
            val appPreference = instruction.extractTrailingAppPreference()
            val cleanedInstruction =
                if (!appPreference.hasTarget) {
                    transcript.cleanCapture()
                } else {
                    transcript.removeSuffixForAppPreference().cleanCapture()
                }
            return UiAgentGoal.Generic(
                instruction = cleanedInstruction,
                targetPackageName = appPreference.packageName,
                targetAppQuery = appPreference.appQuery,
            )
        }

        return null
    }

    private fun genericPhoneGoal(transcript: String): UiAgentGoal.Generic? {
        GENERIC_PHONE_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(transcript)?.groupValues?.get(1)?.cleanCapture()
        }?.takeIf { instruction -> instruction.isNotBlank() }?.let { instruction ->
            val openWorkflowTarget = instruction.openWorkflowTarget()
            val appPreference =
                if (openWorkflowTarget.hasTarget) {
                    openWorkflowTarget
                } else {
                    instruction.extractTrailingAppPreference()
                }
            return UiAgentGoal.Generic(
                instruction = appPreference.cleanedText,
                targetPackageName = appPreference.packageName,
                targetAppQuery = appPreference.appQuery,
            )
        }

        transcript.extractTrailingAppPreference()
            .takeIf { preference ->
                preference.hasTarget &&
                    preference.cleanedText.isNotBlank() &&
                    APP_TASK_PREFIXES.any { prefix ->
                        preference.cleanedText.startsWith(prefix, ignoreCase = true)
                    }
            }?.let { preference ->
                return UiAgentGoal.Generic(
                    instruction = preference.cleanedText,
                    targetPackageName = preference.packageName,
                    targetAppQuery = preference.appQuery,
                )
        }

        return null
    }

    private fun String.cleanCapture(): String =
        trim()
            .trim('"', '\'', '`')
            .trim()

    private fun String.extractTrailingAppPreference(): AppPreferenceMatch {
        val match =
            APP_SUFFIX_PATTERN.find(this)
                ?: return AppPreferenceMatch(cleanedText = cleanCapture(), packageName = null)
        val target = match.groupValues[1].toAppTarget()
        return AppPreferenceMatch(
            cleanedText = removeRange(match.range).cleanCapture(),
            packageName = target.packageName,
            appQuery = target.appQuery,
        )
    }

    private fun String.openWorkflowTarget(): AppPreferenceMatch {
        val match = OPEN_APP_WORKFLOW_PATTERN.matchEntire(this) ?: return AppPreferenceMatch(cleanedText = cleanCapture())
        val target = match.groupValues[1].toAppTarget()
        return AppPreferenceMatch(
            cleanedText = cleanCapture(),
            packageName = target.packageName,
            appQuery = target.appQuery,
        )
    }

    private fun String.removeSuffixForAppPreference(): String {
        val match = APP_SUFFIX_PATTERN.find(this) ?: return this
        return removeRange(match.range)
    }

    private fun String.toAppTarget(): AppTarget {
        val cleaned = cleanCapture().removeLeadingArticle().removeTrailingAppWord()
        if (cleaned.isBlank()) return AppTarget()
        val normalized = cleaned.lowercase()
        APP_PACKAGE_BY_ALIAS[normalized]?.let { packageName ->
            return AppTarget(packageName = packageName)
        }
        if (looksLikePackageName(cleaned)) {
            return AppTarget(packageName = cleaned)
        }
        if (normalized in GENERIC_APP_REFERENCES) {
            return AppTarget()
        }
        return AppTarget(appQuery = cleaned)
    }

    private fun String.removeTrailingAppWord(): String =
        replace(Regex("""\s+(?:app|application)$""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun String.removeLeadingArticle(): String =
        replace(Regex("""^the\s+""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun looksLikePackageName(value: String): Boolean =
        PACKAGE_NAME_PATTERN.matches(value.trim())

    private val MESSAGE_PATTERNS =
        listOf(
            Regex("""(?:send\s+(?:a\s+)?message\s+to|message|text)\s+(.+?)\s+(?:saying|that\s+says|with)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:send|message|text)\s+(.+?)\s*:\s*(.+)""", RegexOption.IGNORE_CASE),
        )

    private val BODYLESS_MESSAGE_PATTERNS =
        listOf(
            Regex("""(?:send\s+(?:a\s+)?message\s+to|message|text)\s+(.+)""", RegexOption.IGNORE_CASE),
        )

    private val GENERIC_PHONE_PATTERNS =
        listOf(
            Regex("""(?:use\s+my\s+phone\s+to|on\s+my\s+phone|on\s+the\s+phone|phone\s+task)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:control\s+my\s+phone\s+and|operate\s+my\s+phone\s+and)\s+(.+)""", RegexOption.IGNORE_CASE),
        )

    private val DIRECT_UI_PATTERNS =
        listOf(
            Regex("""(?:tap|click|press|select)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:set\s+text|type|enter)\s+(.+)""", RegexOption.IGNORE_CASE),
        )

    private val APP_TASK_PREFIXES =
        listOf(
            "message",
            "text",
            "send",
            "play",
            "open",
            "search",
            "find",
            "call",
        )

    private val APP_SUFFIX_PATTERN =
        Regex(
            """\s+(?:on|in|using|via)\s+(?:the\s+)?(?!package\b)([a-zA-Z][a-zA-Z0-9 .&+_-]{1,60}?)(?:\s+app(?:lication)?)?\s*$""",
            RegexOption.IGNORE_CASE,
        )

    private val APP_PACKAGE_BY_ALIAS =
        mapOf(
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "signal" to "org.thoughtcrime.securesms",
            "messages" to "com.google.android.apps.messaging",
            "spotify" to "com.spotify.music",
            "youtube music" to "com.google.android.apps.youtube.music",
            "youtube" to "com.google.android.youtube",
            "brave" to "com.brave.browser",
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "zero assist" to BuildConfig.APPLICATION_ID,
            "zero-assist" to BuildConfig.APPLICATION_ID,
            "zeroclaw" to BuildConfig.APPLICATION_ID,
        )

    private val OPEN_APP_WORKFLOW_PATTERN =
        Regex(
            """(?:open|launch|start)\s+(.+?)(?:\s+(?:and|then)\s+|,\s*).+""",
            RegexOption.IGNORE_CASE,
        )

    private val PACKAGE_NAME_PATTERN =
        Regex("""[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+""")

    private val GENERIC_APP_REFERENCES =
        setOf(
            "app",
            "application",
            "current app",
            "current application",
            "current screen",
            "my phone",
            "phone",
            "screen",
            "the phone",
            "this app",
            "this application",
            "this screen",
        )

    private data class AppTarget(
        val packageName: String? = null,
        val appQuery: String? = null,
    ) {
        val hasTarget: Boolean
            get() = packageName != null || appQuery != null
    }

    private data class AppPreferenceMatch(
        val cleanedText: String,
        val packageName: String? = null,
        val appQuery: String? = null,
    ) {
        val hasTarget: Boolean
            get() = packageName != null || appQuery != null
    }
}
