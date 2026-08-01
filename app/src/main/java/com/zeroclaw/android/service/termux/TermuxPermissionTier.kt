/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

/**
 * User-facing permission tier for Termux command execution.
 *
 * Maps the internal [TermuxCommandRisk] levels to user-controlled
 * approval policies:
 * - **MEDIUM**: LOW auto-allowed, MEDIUM needs approval, HIGH blocked.
 * - **HIGH**: LOW+MEDIUM+HIGH auto-allowed, BLOCKED needs approval.
 * - **UNCONSTRAINED**: everything allowed, nothing blocked.
 */
enum class TermuxPermissionTier {
    MEDIUM,
    HIGH,
    UNCONSTRAINED,
}
