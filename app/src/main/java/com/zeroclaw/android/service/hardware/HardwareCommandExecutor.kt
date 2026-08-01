/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.hardware

import com.zeroclaw.android.model.HardwareCommand
import com.zeroclaw.android.model.HardwareCommandResult
import com.zeroclaw.android.model.HardwareDevice

/**
 * Runtime boundary for executing hardware protocol commands.
 */
interface HardwareCommandExecutor {
    suspend fun execute(
        device: HardwareDevice,
        command: HardwareCommand,
    ): HardwareCommandResult
}
