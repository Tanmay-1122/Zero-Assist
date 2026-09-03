/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CUSTOM_VOICE_PACKAGE_FORMAT_VERSION
import com.zeroclaw.android.model.CUSTOM_VOICE_PACKAGE_MANIFEST_FILE
import com.zeroclaw.android.model.CustomVoicePhonemizerType
import com.zeroclaw.android.model.CustomVoicePackageManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeType

data class CustomVoicePackageValidationResult(
    val issues: List<CustomVoicePackageValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.isEmpty()
}

data class CustomVoicePackageValidationIssue(
    val field: String,
    val message: String,
)

/**
 * Validates the package-level contract for imported/downloaded local voices.
 *
 * The validator deliberately checks only metadata and package-relative paths.
 * Actual runtime loading is a separate local-only implementation step.
 */
class CustomVoicePackageValidator(
    private val supportedRuntimeTypes: Set<String> = CustomVoiceRuntimeType.supportedLocalTypes,
) {
    fun validate(manifest: CustomVoicePackageManifest): CustomVoicePackageValidationResult {
        val issues = mutableListOf<CustomVoicePackageValidationIssue>()

        if (manifest.formatVersion != CUSTOM_VOICE_PACKAGE_FORMAT_VERSION) {
            issues += issue("formatVersion", "Unsupported custom voice package format version.")
        }
        if (!manifest.packageId.isValidPackageId()) {
            issues += issue("packageId", "Voice package id is missing or invalid.")
        }
        if (manifest.displayName.isBlank()) {
            issues += issue("displayName", "Voice display name is required.")
        }
        if (!manifest.localeTag.trim().startsWith("en", ignoreCase = true)) {
            issues += issue("localeTag", "Only English voice packages are supported first.")
        }
        if (manifest.sampleText.isBlank()) {
            issues += issue("sampleText", "Voice sample text is required.")
        }

        val runtimeType = manifest.runtime.type.trim()
        if (runtimeType !in supportedRuntimeTypes) {
            issues += issue("runtime.type", "Voice runtime type is not supported.")
        }
        if (manifest.runtime.requiresNetwork) {
            issues += issue("runtime.requiresNetwork", "Cloud or network voice runtimes are blocked.")
        }
        if (manifest.runtime.requiresExternalHardware) {
            issues +=
                issue(
                    "runtime.requiresExternalHardware",
                    "External hardware voice runtimes are blocked.",
                )
        }
        if (manifest.runtime.sampleRateHz !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            issues += issue("runtime.sampleRateHz", "Voice sample rate is invalid.")
        }

        val model = manifest.model
        if (model.path.isUnsafePackagePath()) {
            issues += issue("model.path", "Voice model path must be package-relative and local.")
        } else if (model.path.normalizedPackagePath() == CUSTOM_VOICE_PACKAGE_MANIFEST_FILE) {
            issues += issue("model.path", "Voice model path cannot overwrite the package manifest.")
        }
        if (model.configPath?.isUnsafePackagePath() == true) {
            issues += issue("model.configPath", "Voice config path must be package-relative and local.")
        } else if (model.configPath?.normalizedPackagePath() == CUSTOM_VOICE_PACKAGE_MANIFEST_FILE) {
            issues += issue("model.configPath", "Voice config path cannot overwrite the package manifest.")
        }
        if (model.sizeBytes <= 0L) {
            issues += issue("model.sizeBytes", "Voice model size metadata is required.")
        }
        if (!model.sha256.isValidSha256()) {
            issues += issue("model.sha256", "Voice model SHA-256 metadata is required.")
        }

        val phonemizer = manifest.phonemizer
        if (phonemizer != null) {
            if (phonemizer.type.trim() !in CustomVoicePhonemizerType.supportedLocalTypes) {
                issues += issue("phonemizer.type", "Voice phonemizer type is not supported.")
            }
            if (phonemizer.path.isUnsafePackagePath()) {
                issues += issue(
                    "phonemizer.path",
                    "Voice phonemizer path must be package-relative and local.",
                )
            } else if (phonemizer.path.normalizedPackagePath() == CUSTOM_VOICE_PACKAGE_MANIFEST_FILE) {
                issues +=
                    issue(
                        "phonemizer.path",
                        "Voice phonemizer path cannot overwrite the package manifest.",
                    )
            }
            if (phonemizer.sizeBytes <= 0L) {
                issues += issue("phonemizer.sizeBytes", "Voice phonemizer size metadata is required.")
            }
            if (!phonemizer.sha256.isValidSha256()) {
                issues +=
                    issue(
                        "phonemizer.sha256",
                        "Voice phonemizer SHA-256 metadata is required.",
                    )
            }
        }

        return CustomVoicePackageValidationResult(issues)
    }

    private fun issue(
        field: String,
        message: String,
    ): CustomVoicePackageValidationIssue =
        CustomVoicePackageValidationIssue(
            field = field,
            message = message,
        )

}

private const val MIN_SAMPLE_RATE_HZ = 8_000
private const val MAX_SAMPLE_RATE_HZ = 48_000
private val PACKAGE_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val WINDOWS_ABSOLUTE_PATH_PATTERN = Regex("^[a-zA-Z]:[\\\\/].*")
private val URI_SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")
private val SHA_256_PATTERN = Regex("^[a-fA-F0-9]{64}$")

private fun String.isValidPackageId(): Boolean =
    trim().matches(PACKAGE_ID_PATTERN)

private fun String.isUnsafePackagePath(): Boolean {
    val normalized = trim().replace('\\', '/')
    if (normalized.isBlank()) return true
    if (normalized.any { it.code < 32 }) return true
    if (normalized.startsWith("/") || normalized.startsWith("~")) return true
    if (normalized.startsWith("//")) return true
    if (matches(WINDOWS_ABSOLUTE_PATH_PATTERN)) return true
    if (matches(URI_SCHEME_PATTERN)) return true

    val segments = normalized.split('/').filter { it.isNotBlank() }
    return segments.any { segment ->
        segment == "." || segment == ".."
    }
}

private fun String.isValidSha256(): Boolean =
    trim().matches(SHA_256_PATTERN)

private fun String.normalizedPackagePath(): String =
    trim().replace('\\', '/')
