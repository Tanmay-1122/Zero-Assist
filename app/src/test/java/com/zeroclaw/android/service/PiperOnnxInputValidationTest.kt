/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Piper ONNX input validation")
class PiperOnnxInputValidationTest {
    @Test
    fun `accepts bounded non-negative input ids`() {
        assertNull(validatePiperInputIdsForOnnx(longArrayOf(1L, 2L, 3L)))
    }

    @Test
    fun `rejects empty input ids before native ONNX call`() {
        assertTrue(
            validatePiperInputIdsForOnnx(longArrayOf())
                .orEmpty()
                .contains("empty"),
        )
    }

    @Test
    fun `rejects oversized input ids before native ONNX call`() {
        assertTrue(
            validatePiperInputIdsForOnnx(LongArray(257) { 1L })
                .orEmpty()
                .contains("too large"),
        )
    }

    @Test
    fun `rejects negative input ids before native ONNX call`() {
        assertTrue(
            validatePiperInputIdsForOnnx(longArrayOf(1L, -1L))
                .orEmpty()
                .contains("invalid phoneme id"),
        )
    }
}
