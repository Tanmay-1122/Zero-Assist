/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.model.OfficialPlugins

/**
 * Persists the AppSettings flag that backs an official plugin switch.
 *
 * @return true when [pluginId] maps to a mutable setting, false for always-on
 *   or unknown official plugins.
 */
suspend fun SettingsRepository.setOfficialPluginEnabled(
    pluginId: String,
    enabled: Boolean,
): Boolean =
    when (pluginId) {
        OfficialPlugins.WEB_SEARCH -> {
            setWebSearchEnabled(enabled)
            true
        }
        OfficialPlugins.WEB_FETCH -> {
            setWebFetchEnabled(enabled)
            true
        }
        OfficialPlugins.HTTP_REQUEST -> {
            setHttpRequestEnabled(enabled)
            true
        }
        OfficialPlugins.BROWSER -> {
            setBrowserEnabled(enabled)
            true
        }
        OfficialPlugins.COMPOSIO -> {
            setComposioEnabled(enabled)
            true
        }
        OfficialPlugins.SHARED_FOLDER -> {
            setSharedFolderEnabled(enabled)
            true
        }
        OfficialPlugins.WORKFLOW_FOLDER -> {
            setWorkflowFolderEnabled(enabled)
            true
        }
        OfficialPlugins.LINUX_SANDBOX -> {
            setLinuxSandboxEnabled(enabled)
            true
        }
        OfficialPlugins.GOOGLE_WORKSPACE -> {
            setGoogleWorkspaceEnabled(enabled)
            true
        }
        OfficialPlugins.TERMUX -> {
            setTermuxEnabled(enabled)
            true
        }
        else -> false
    }
