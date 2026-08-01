/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/** The Android-side interpretation of the configured Composio key. */
enum class ComposioKeyMode {
    Disabled,
    Missing,
    Sessions,
    LegacyRest,
    CliUser,
}

/**
 * Readiness state for the Composio integration.
 *
 * Android accepts both current `ck_` Sessions/MCP consumer keys and legacy REST
 * keys, but a blank key or `uak_` CLI login key cannot activate tools.
 */
data class ComposioReadiness(
    val mode: ComposioKeyMode,
    val isActive: Boolean,
    val inactiveReason: String,
) {
    val usesSessionsKey: Boolean = mode == ComposioKeyMode.Sessions
    val usesCliUserKey: Boolean = mode == ComposioKeyMode.CliUser
    val usesLegacyRestKey: Boolean = mode == ComposioKeyMode.LegacyRest

    val statusMessage: String =
        when (mode) {
            ComposioKeyMode.Disabled -> "Composio is disabled."
            ComposioKeyMode.Missing ->
                "Composio inactive: add a ck_ Sessions/MCP key or a legacy REST project key."
            ComposioKeyMode.Sessions ->
                "ck_ Sessions/MCP key detected. Connected account tools use your Composio " +
                    "account; entity ID is ignored."
            ComposioKeyMode.LegacyRest ->
                "Legacy REST key detected. Entity ID selects the Composio user, usually default."
            ComposioKeyMode.CliUser ->
                "Composio inactive: uak_ is a CLI login key. Use a ck_ Sessions/MCP " +
                    "consumer key or legacy REST project key."
        }

    companion object {
        fun from(
            enabled: Boolean,
            apiKey: String,
        ): ComposioReadiness {
            if (!enabled) {
                return ComposioReadiness(
                    mode = ComposioKeyMode.Disabled,
                    isActive = false,
                    inactiveReason = "Composio is disabled in Settings.",
                )
            }

            val trimmedKey = apiKey.trim()
            if (trimmedKey.isBlank()) {
                return ComposioReadiness(
                    mode = ComposioKeyMode.Missing,
                    isActive = false,
                    inactiveReason = "Add a ck_ Sessions/MCP key or a legacy REST project key.",
                )
            }
            if (trimmedKey.startsWith(COMPOSIO_CLI_USER_KEY_PREFIX)) {
                return ComposioReadiness(
                    mode = ComposioKeyMode.CliUser,
                    isActive = false,
                    inactiveReason = "uak_ is a Composio CLI login key, not a tools key.",
                )
            }

            return ComposioReadiness(
                mode =
                    if (trimmedKey.startsWith(COMPOSIO_SESSIONS_KEY_PREFIX)) {
                        ComposioKeyMode.Sessions
                    } else {
                        ComposioKeyMode.LegacyRest
                    },
                isActive = true,
                inactiveReason = "",
            )
        }
    }
}

const val COMPOSIO_SESSIONS_KEY_PREFIX = "ck_"
const val COMPOSIO_CLI_USER_KEY_PREFIX = "uak_"
