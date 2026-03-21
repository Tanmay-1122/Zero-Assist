/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import kotlinx.serialization.Serializable

/**
 * Per-agent DroidRun LLM override used for phone-task execution.
 *
 * The selected connection provides the credential and optional base URL,
 * while [provider] and [modelName] describe the desired runtime model.
 *
 * @property connectionId Saved connection ID whose credential should be used.
 * @property provider Canonical provider ID for the DroidRun LLM.
 * @property modelName Model identifier to use for DroidRun tasks.
 */
@Serializable
data class DroidRunAgentConfig(
    val connectionId: String,
    val provider: String,
    val modelName: String,
)
