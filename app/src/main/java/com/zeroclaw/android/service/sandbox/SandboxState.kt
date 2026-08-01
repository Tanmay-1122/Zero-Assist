/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

sealed interface SandboxState {
    data object NotInstalled : SandboxState
    data class Downloading(val progress: Float) : SandboxState
    data object Extracting : SandboxState
    data class Installing(val detail: String = "") : SandboxState
    data object Ready : SandboxState
    data class Error(val message: String, val recoverable: Boolean = true) : SandboxState
}
