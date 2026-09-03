/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ForegroundWindowState(
    val packageName: String? = null,
    val windowTitle: String? = null,
    val rootReady: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
)

interface ForegroundWindowTracker {
    val state: StateFlow<ForegroundWindowState>

    fun updateForeground(
        packageName: String?,
        windowTitle: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): ForegroundWindowState

    fun markRootReady(
        packageName: String?,
        windowTitle: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): ForegroundWindowState

    fun clear(timestampMs: Long = System.currentTimeMillis()): ForegroundWindowState
}

class InMemoryForegroundWindowTracker(
    initialState: ForegroundWindowState = ForegroundWindowState(),
) : ForegroundWindowTracker {
    private val mutableState = MutableStateFlow(initialState)

    override val state: StateFlow<ForegroundWindowState> = mutableState.asStateFlow()

    override fun updateForeground(
        packageName: String?,
        windowTitle: String?,
        timestampMs: Long,
    ): ForegroundWindowState {
        val sanitizedPackageName = packageName.sanitizeForegroundText()
        val current = mutableState.value
        val stablePackageName =
            if (
                sanitizedPackageName.isTransientForegroundPackage() &&
                !current.packageName.isTransientForegroundPackage()
            ) {
                current.packageName
            } else {
                sanitizedPackageName
            }
        val stableWindowTitle =
            if (stablePackageName == current.packageName && sanitizedPackageName != stablePackageName) {
                current.windowTitle
            } else {
                windowTitle.sanitizeForegroundText()
            }
        return ForegroundWindowState(
            packageName = stablePackageName,
            windowTitle = stableWindowTitle,
            rootReady = false,
            updatedAtEpochMs = timestampMs,
        ).also { mutableState.value = it }
    }

    override fun markRootReady(
        packageName: String?,
        windowTitle: String?,
        timestampMs: Long,
    ): ForegroundWindowState {
        val current = mutableState.value
        val sanitizedPackageName = packageName.sanitizeForegroundText()
        val isTransientPackage =
            sanitizedPackageName.isTransientForegroundPackage() &&
                !current.packageName.isTransientForegroundPackage()
        val stablePackageName =
            if (isTransientPackage) {
                current.packageName
            } else {
                sanitizedPackageName ?: current.packageName
            }
        val stableWindowTitle =
            if (isTransientPackage && stablePackageName == current.packageName) {
                current.windowTitle
            } else {
                windowTitle.sanitizeForegroundText() ?: current.windowTitle
            }
        val next =
            ForegroundWindowState(
                packageName = stablePackageName,
                windowTitle = stableWindowTitle,
                rootReady = true,
                updatedAtEpochMs = timestampMs,
            )
        mutableState.value = next
        return next
    }

    override fun clear(timestampMs: Long): ForegroundWindowState =
        ForegroundWindowState(updatedAtEpochMs = timestampMs)
            .also { mutableState.value = it }
}

private fun String?.sanitizeForegroundText(): String? =
    UiTextSanitizer.sanitize(this)

internal fun String?.isTransientForegroundPackage(): Boolean {
    val value = this?.trim()?.lowercase().orEmpty()
    return value.isSystemUiPackage() || value.isInputMethodPackage()
}

internal fun String?.isSystemUiPackage(): Boolean {
    val value = this?.trim()?.lowercase().orEmpty()
    return value == "com.android.systemui" ||
        value.startsWith("com.android.systemui.")
}

internal fun String?.isInputMethodPackage(): Boolean {
    val value = this?.trim()?.lowercase().orEmpty()
    return value.contains(".inputmethod.") ||
        value.endsWith(".inputmethod") ||
        value == "com.google.android.inputmethod.latin"
}
