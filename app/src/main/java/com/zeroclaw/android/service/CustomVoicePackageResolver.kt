/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CUSTOM_VOICE_PACKAGE_MANIFEST_FILE
import com.zeroclaw.android.model.CustomVoicePackageManifest
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class ResolvedCustomVoicePackage(
    val manifest: CustomVoicePackageManifest,
    val packageRoot: File,
    val manifestFile: File,
    val modelFile: File,
    val configFile: File?,
    val phonemizerFile: File?,
)

sealed interface CustomVoicePackageResolveResult {
    data class Success(val voicePackage: ResolvedCustomVoicePackage) :
        CustomVoicePackageResolveResult

    data class Failure(val message: String) : CustomVoicePackageResolveResult
}

/**
 * Resolves an already-imported custom voice package into local files.
 *
 * The resolver accepts only app-local file URIs for stored package manifests.
 * It does not load a speech runtime; it verifies the package boundary a future
 * local runtime will consume.
 */
class CustomVoicePackageResolver(
    private val validator: CustomVoicePackageValidator = CustomVoicePackageValidator(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
        },
) {
    fun resolve(manifestUri: String): CustomVoicePackageResolveResult {
        val manifestFile =
            manifestUri.toLocalFile()
                ?: return CustomVoicePackageResolveResult.Failure(
                    "Custom voice package must use a local file URI.",
                )
        if (manifestFile.name != CUSTOM_VOICE_PACKAGE_MANIFEST_FILE) {
            return CustomVoicePackageResolveResult.Failure(
                "Custom voice package manifest URI is required.",
            )
        }
        if (!manifestFile.isFile) {
            return CustomVoicePackageResolveResult.Failure(
                "Custom voice package manifest is missing.",
            )
        }

        val packageRoot =
            manifestFile.parentFile
                ?: return CustomVoicePackageResolveResult.Failure(
                    "Custom voice package root is missing.",
                )

        val manifest =
            try {
                json.decodeFromString<CustomVoicePackageManifest>(manifestFile.readText())
            } catch (e: SerializationException) {
                return CustomVoicePackageResolveResult.Failure(
                    "Custom voice package manifest could not be read.",
                )
            } catch (e: IOException) {
                return CustomVoicePackageResolveResult.Failure(
                    "Custom voice package manifest could not be opened.",
                )
            }

        val validation = validator.validate(manifest)
        if (!validation.isValid) {
            return CustomVoicePackageResolveResult.Failure(
                "Custom voice package is invalid: ${validation.issues.first().message}",
            )
        }

        val modelFile =
            try {
                packageRoot.safePackageChild(manifest.model.path)
            } catch (e: IOException) {
                return CustomVoicePackageResolveResult.Failure(
                    "Custom voice package model path is invalid.",
                )
            }
        if (!modelFile.isFile) {
            return CustomVoicePackageResolveResult.Failure(
                "Custom voice package model file is missing.",
            )
        }
        if (modelFile.length() != manifest.model.sizeBytes) {
            return CustomVoicePackageResolveResult.Failure(
                "Custom voice package model size does not match manifest.",
            )
        }
        if (!modelFile.sha256().equals(manifest.model.sha256, ignoreCase = true)) {
            return CustomVoicePackageResolveResult.Failure(
                "Custom voice package model checksum does not match manifest.",
            )
        }

        val configFile =
            manifest.model.configPath?.let { path ->
                val file =
                    try {
                        packageRoot.safePackageChild(path)
                    } catch (e: IOException) {
                        return CustomVoicePackageResolveResult.Failure(
                            "Custom voice package config path is invalid.",
                        )
                    }
                file.also {
                    if (!file.isFile) {
                        return CustomVoicePackageResolveResult.Failure(
                            "Custom voice package config file is missing.",
                        )
                    }
                }
            }

        val phonemizerFile =
            manifest.phonemizer?.let { phonemizer ->
                val file =
                    try {
                        packageRoot.safePackageChild(phonemizer.path)
                    } catch (e: IOException) {
                        return CustomVoicePackageResolveResult.Failure(
                            "Custom voice package phonemizer path is invalid.",
                        )
                    }
                if (!file.isFile) {
                    return CustomVoicePackageResolveResult.Failure(
                        "Custom voice package phonemizer file is missing.",
                    )
                }
                if (file.length() != phonemizer.sizeBytes) {
                    return CustomVoicePackageResolveResult.Failure(
                        "Custom voice package phonemizer size does not match manifest.",
                    )
                }
                if (!file.sha256().equals(phonemizer.sha256, ignoreCase = true)) {
                    return CustomVoicePackageResolveResult.Failure(
                        "Custom voice package phonemizer checksum does not match manifest.",
                    )
                }
                file
            }

        return CustomVoicePackageResolveResult.Success(
            ResolvedCustomVoicePackage(
                manifest = manifest,
                packageRoot = packageRoot,
                manifestFile = manifestFile,
                modelFile = modelFile,
                configFile = configFile,
                phonemizerFile = phonemizerFile,
            ),
        )
    }

    private fun String.toLocalFile(): File? {
        val uri = runCatching { URI(trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) {
            return null
        }
        return runCatching { File(uri).canonicalFile }.getOrNull()
    }

    private fun File.safePackageChild(packageRelativePath: String): File {
        val child = File(this, packageRelativePath.trim().replace('\\', '/'))
        val rootPath = canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        if (!childPath.startsWith(rootPath)) {
            throw IOException("Unsafe package path.")
        }
        return child
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
