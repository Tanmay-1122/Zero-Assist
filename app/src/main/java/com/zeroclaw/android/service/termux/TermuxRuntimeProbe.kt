/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

data class TermuxRuntimeProbeSnapshot(
    val packageState: TermuxPackageState,
    val permissionState: TermuxPermissionState,
    val bootstrapState: TermuxBootstrapState,
)

interface TermuxRuntimeProbe {
    fun snapshot(): TermuxRuntimeProbeSnapshot
}

class AndroidTermuxRuntimeProbe(
    private val context: Context,
    private val intentBoundary: TermuxIntentBoundary = TermuxIntentBoundary(),
) : TermuxRuntimeProbe {
    override fun snapshot(): TermuxRuntimeProbeSnapshot =
        TermuxRuntimeProbeSnapshot(
            packageState = packageState(),
            permissionState = permissionState(),
            bootstrapState = bootstrapState(),
        )

    private fun packageState(): TermuxPackageState =
        try {
            val info = context.packageManager.termuxPackageInfo()
            TermuxPackageState(
                availability = TermuxPackageAvailability.INSTALLED,
                versionName = info.versionName,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            TermuxPackageState(availability = TermuxPackageAvailability.NOT_INSTALLED)
        } catch (_: RuntimeException) {
            TermuxPackageState(availability = TermuxPackageAvailability.NOT_VISIBLE)
        }

    private fun permissionState(): TermuxPermissionState {
        val state =
            try {
                if (
                    context.checkSelfPermission(TermuxRuntimeContract.RUN_COMMAND_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    TermuxPermissionAvailability.GRANTED
                } else {
                    TermuxPermissionAvailability.DENIED
                }
            } catch (_: RuntimeException) {
                TermuxPermissionAvailability.UNKNOWN
            }
        return TermuxPermissionState(availability = state)
    }

    private fun bootstrapState(): TermuxBootstrapState {
        val spec = intentBoundary.bootstrapIntent()
        val androidIntent = spec.toAndroidIntent()
        val canResolve =
            try {
                context.packageManager.resolveActivity(androidIntent, 0) != null ||
                    context.packageManager.getLaunchIntentForPackage(
                        TermuxRuntimeContract.TERMUX_PACKAGE_NAME,
                    ) != null
            } catch (_: RuntimeException) {
                false
            }
        return TermuxBootstrapState(
            availability =
                if (canResolve) {
                    TermuxBootstrapAvailability.AVAILABLE
                } else {
                    TermuxBootstrapAvailability.UNAVAILABLE
                },
            intentSpec = spec,
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.termuxPackageInfo(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(
                TermuxRuntimeContract.TERMUX_PACKAGE_NAME,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            getPackageInfo(TermuxRuntimeContract.TERMUX_PACKAGE_NAME, 0)
        }
}
