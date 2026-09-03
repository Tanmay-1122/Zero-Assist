/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PiperOnnxRuntimeThreadPolicy")
class PiperOnnxRuntimeThreadPolicyTest {
    @Test
    fun `limits low tier devices to one synthesis thread`() {
        assertEquals(
            PiperOnnxRuntimeThreadPolicy(
                intraOpNumThreads = 1,
                interOpNumThreads = 1,
            ),
            PiperOnnxRuntimeThreadPolicy.default(
                VoiceDeviceProfile(
                    tier = VoiceDeviceTier.LOW,
                    totalRamMb = 3_072L,
                    availableProcessors = 8,
                ),
            ),
        )
    }

    @Test
    fun `uses two synthesis threads for mid tier devices`() {
        assertEquals(
            PiperOnnxRuntimeThreadPolicy(
                intraOpNumThreads = 2,
                interOpNumThreads = 1,
            ),
            PiperOnnxRuntimeThreadPolicy.default(
                VoiceDeviceProfile(
                    tier = VoiceDeviceTier.MID,
                    totalRamMb = 6_144L,
                    availableProcessors = 6,
                ),
            ),
        )
    }

    @Test
    fun `caps high tier devices at two synthesis threads`() {
        assertEquals(
            PiperOnnxRuntimeThreadPolicy(
                intraOpNumThreads = 2,
                interOpNumThreads = 1,
            ),
            PiperOnnxRuntimeThreadPolicy.default(
                VoiceDeviceProfile(
                    tier = VoiceDeviceTier.HIGH,
                    totalRamMb = 8_192L,
                    availableProcessors = 12,
                ),
            ),
        )
    }

    @Test
    fun `never exceeds available processors`() {
        assertEquals(
            PiperOnnxRuntimeThreadPolicy(
                intraOpNumThreads = 2,
                interOpNumThreads = 1,
            ),
            PiperOnnxRuntimeThreadPolicy.default(
                VoiceDeviceProfile(
                    tier = VoiceDeviceTier.HIGH,
                    totalRamMb = 8_192L,
                    availableProcessors = 2,
                ),
            ),
        )
    }
}
