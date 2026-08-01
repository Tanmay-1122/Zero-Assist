/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import kotlinx.serialization.Serializable

const val CUSTOM_VOICE_PACKAGE_FORMAT_VERSION = 1
const val CUSTOM_VOICE_PACKAGE_MANIFEST_FILE = "voice-package.json"

object CustomVoiceRuntimeType {
    const val PIPER_V1 = "piper-v1"
    const val ONNX_VITS_V1 = "onnx-vits-v1"

    val supportedLocalTypes: Set<String> =
        setOf(
            PIPER_V1,
            ONNX_VITS_V1,
        )
}

object CustomVoicePhonemizerType {
    const val PIPER_LEXICON_V1 = "piper-lexicon-v1"

    val supportedLocalTypes: Set<String> =
        setOf(
            PIPER_LEXICON_V1,
        )
}

@Serializable
data class CustomVoicePackageManifest(
    val formatVersion: Int = CUSTOM_VOICE_PACKAGE_FORMAT_VERSION,
    val packageId: String,
    val displayName: String,
    val localeTag: String,
    val sampleText: String,
    val runtime: CustomVoiceRuntimeManifest,
    val model: CustomVoiceModelFileManifest,
    val phonemizer: CustomVoicePhonemizerManifest? = null,
)

@Serializable
data class CustomVoiceRuntimeManifest(
    val type: String,
    val sampleRateHz: Int,
    val requiresNetwork: Boolean = false,
    val requiresExternalHardware: Boolean = false,
)

@Serializable
data class CustomVoiceModelFileManifest(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val configPath: String? = null,
)

@Serializable
data class CustomVoicePhonemizerManifest(
    val type: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)
