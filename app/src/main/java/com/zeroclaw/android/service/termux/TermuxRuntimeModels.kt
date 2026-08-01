/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

/**
 * Stable constants for the Termux runtime integration boundary.
 *
 * These values mirror the public Termux RUN_COMMAND intent contract, but this
 * package does not start the service or execute commands.
 */
object TermuxRuntimeContract {
    const val TERMUX_PACKAGE_NAME = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    const val RUN_COMMAND_SERVICE_CLASS_NAME = "com.termux.app.RunCommandService"
    const val DEFAULT_BRIDGE_HOST = "127.0.0.1"
    const val DEFAULT_BRIDGE_PORT = 8787
    const val DEFAULT_BRIDGE_BASE_URL = "http://127.0.0.1:8787"
    const val FALLBACK_BRIDGE_BASE_URL = "http://localhost:8787"
    const val BRIDGE_TOKEN_HEADER = "X-Zero-Assist-Termux-Token"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
}

enum class TermuxPackageAvailability {
    INSTALLED,
    NOT_INSTALLED,
    NOT_VISIBLE,
}

data class TermuxPackageState(
    val availability: TermuxPackageAvailability,
    val packageName: String = TermuxRuntimeContract.TERMUX_PACKAGE_NAME,
    val versionName: String? = null,
)

enum class TermuxPermissionAvailability {
    GRANTED,
    DENIED,
    UNKNOWN,
}

data class TermuxPermissionState(
    val permissionName: String = TermuxRuntimeContract.RUN_COMMAND_PERMISSION,
    val availability: TermuxPermissionAvailability,
)

enum class TermuxBootstrapAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

data class TermuxBootstrapState(
    val availability: TermuxBootstrapAvailability,
    val intentSpec: TermuxIntentSpec? = null,
)

enum class TermuxHealthStatus {
    READY,
    UNAVAILABLE,
    UNKNOWN,
}

data class TermuxProotState(
    val available: Boolean? = null,
    val activeDistro: String? = null,
    val distros: List<String> = emptyList(),
)

data class TermuxBridgeHealthDetails(
    val endpoint: String? = null,
    val version: String? = null,
    val workspace: String? = null,
    val proot: TermuxProotState = TermuxProotState(),
)

data class TermuxHealthSnapshot(
    val status: TermuxHealthStatus,
    val reason: String,
    val details: TermuxBridgeHealthDetails = TermuxBridgeHealthDetails(),
)

data class TermuxRuntimeStatus(
    val packageState: TermuxPackageState,
    val permissionState: TermuxPermissionState,
    val bootstrapState: TermuxBootstrapState,
    val health: TermuxHealthSnapshot,
) {
    val isReady: Boolean
        get() =
            packageState.availability == TermuxPackageAvailability.INSTALLED &&
                permissionState.availability == TermuxPermissionAvailability.GRANTED &&
                health.status == TermuxHealthStatus.READY

    fun inactiveReason(): String =
        when {
            packageState.availability != TermuxPackageAvailability.INSTALLED ->
                "Install Termux before enabling local runtime tools."
            permissionState.availability != TermuxPermissionAvailability.GRANTED ->
                "Grant Zero-Assist the Termux RUN_COMMAND permission in Android app settings."
            health.status != TermuxHealthStatus.READY ->
                health.reason.ifBlank { "Termux runtime health has not been confirmed." }
            else -> ""
        }
}
