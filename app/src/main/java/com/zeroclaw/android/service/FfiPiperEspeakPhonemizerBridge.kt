/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.getPiperPhonemizerStatus

internal data class NativePiperPhonemizerStatus(
    val available: Boolean,
    val engine: String,
    val detail: String,
)

/**
 * UniFFI-backed readiness bridge for the future native Piper/eSpeak phonemizer.
 *
 * This bridge deliberately fails closed until the Rust layer exposes a real
 * phonemize-to-Piper-ids call and the Android build bundles the native engine.
 */
internal class FfiPiperEspeakPhonemizerBridge(
    private val statusProvider: () -> NativePiperPhonemizerStatus = ::loadNativeStatus,
) : PiperEspeakPhonemizerBridge {
    override fun readiness(config: PiperVoiceConfig): PiperPhonemizerReadiness {
        val status = statusProvider()
        return if (status.available) {
            PiperPhonemizerReadiness.Ready
        } else {
            PiperPhonemizerReadiness.Unavailable(status.detail)
        }
    }

    override fun phonemize(
        text: String,
        config: PiperVoiceConfig,
    ): PiperPhonemeEncodeResult {
        val status = statusProvider()
        if (!status.available) {
            return PiperPhonemeEncodeResult.Failure(status.detail)
        }
        return PiperPhonemeEncodeResult.Failure(
            "Native Piper/eSpeak phonemizer '${status.engine}' is available, but phoneme ID binding is not implemented yet.",
        )
    }

    private companion object {
        private fun loadNativeStatus(): NativePiperPhonemizerStatus =
            runCatching {
                val status = getPiperPhonemizerStatus()
                NativePiperPhonemizerStatus(
                    available = status.available,
                    engine = status.engine,
                    detail = status.detail,
                )
            }.getOrElse { error ->
                NativePiperPhonemizerStatus(
                    available = false,
                    engine = "ffi-unavailable",
                    detail =
                        "Native Piper/eSpeak phonemizer bridge is unavailable: ${error.readableMessage()}",
                )
            }

        private fun Throwable.readableMessage(): String {
            val rawMessage = message?.ifBlank { null } ?: return javaClass.simpleName
            return rawMessage
                .substringBefore(" in resource path")
                .substringBefore("\n")
                .trim()
                .ifBlank { javaClass.simpleName }
        }
    }
}
