/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceOutputRoutingPolicy")
class VoiceOutputRoutingPolicyTest {
    @Test
    fun `classifies constrained phones as low tier`() {
        assertEquals(
            VoiceDeviceTier.LOW,
            VoiceDeviceProfileClassifier.classify(
                totalRamMb = 3_072L,
                availableProcessors = 8,
            ),
        )
        assertEquals(
            VoiceDeviceTier.LOW,
            VoiceDeviceProfileClassifier.classify(
                totalRamMb = 6_144L,
                availableProcessors = 4,
            ),
        )
    }

    @Test
    fun `classifies high memory octa core phones as high tier`() {
        assertEquals(
            VoiceDeviceTier.HIGH,
            VoiceDeviceProfileClassifier.classify(
                totalRamMb = 8_192L,
                availableProcessors = 8,
            ),
        )
    }

    @Test
    fun `classifies middle devices conservatively`() {
        assertEquals(
            VoiceDeviceTier.MID,
            VoiceDeviceProfileClassifier.classify(
                totalRamMb = 6_144L,
                availableProcessors = 6,
            ),
        )
    }
}
