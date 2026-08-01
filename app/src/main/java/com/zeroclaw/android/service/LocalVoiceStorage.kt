/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import com.zeroclaw.android.model.CUSTOM_VOICE_PACKAGE_MANIFEST_FILE
import com.zeroclaw.android.model.CustomVoiceModelFileManifest
import com.zeroclaw.android.model.CustomVoicePackageManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DEFAULT_IMPORTED_VOICE_SAMPLE_TEXT =
    "This imported voice is ready for local playback."

data class VoicePackageImportRequest(
    val displayName: String,
    val localeTag: String,
    val sourceUri: String,
    val declaredSizeBytes: Long,
)

data class VoicePackageImportFile(
    val displayName: String,
    val sourceUri: String,
    val declaredSizeBytes: Long,
)

data class VoicePackageImportSelectionRequest(
    val localeTag: String,
    val files: List<VoicePackageImportFile>,
)

data class StoredVoicePackage(
    val displayName: String,
    val localeTag: String,
    val localModelUri: String,
    val sizeBytes: Long,
    val sampleText: String = DEFAULT_IMPORTED_VOICE_SAMPLE_TEXT,
    val runtimeType: String? = null,
)

sealed interface VoicePackageImportResult {
    data class Success(val voicePackage: StoredVoicePackage) : VoicePackageImportResult
    data class Failure(val message: String) : VoicePackageImportResult
}

interface VoicePackageInputOpener {
    fun open(sourceUri: String): InputStream?
}

open class LocalVoiceStorage(
    private val storageRoot: File,
    private val inputOpener: VoicePackageInputOpener = FileVoicePackageInputOpener,
    private val packageValidator: CustomVoicePackageValidator = CustomVoicePackageValidator(),
) {
    fun importSelectedFiles(request: VoicePackageImportSelectionRequest): VoicePackageImportResult {
        val files = request.files.filter { file -> file.sourceUri.isNotBlank() }
        if (files.isEmpty()) {
            return VoicePackageImportResult.Failure("Select a voice package or Piper voice files.")
        }
        if (!request.localeTag.trim().startsWith("en", ignoreCase = true)) {
            return VoicePackageImportResult.Failure("Only English voice imports are supported first.")
        }
        files.firstNotNullOfOrNull { file -> validateSelectedFile(file) }?.let { error ->
            return VoicePackageImportResult.Failure(error)
        }

        if (files.size == 1) {
            val file = files.single()
            if (file.isVoicePackageBundle()) {
                return importPackage(
                    VoicePackageImportRequest(
                        displayName = file.displayName,
                        localeTag = request.localeTag,
                        sourceUri = file.sourceUri,
                        declaredSizeBytes = file.declaredSizeBytes,
                    ),
                )
            }
            return VoicePackageImportResult.Failure(
                "Piper voices need both files. Select the .onnx model and matching .onnx.json config together.",
            )
        }

        if (files.size != PIPER_PAIR_FILE_COUNT) {
            return VoicePackageImportResult.Failure(
                "Select one .voicepkg/.zip, or exactly one .onnx and one matching .onnx.json file.",
            )
        }

        val pair =
            findPiperVoicePair(files)
                ?: return VoicePackageImportResult.Failure(
                    "Piper voice filenames must match, like voice.onnx and voice.onnx.json.",
                )
        return importPiperVoicePair(request.localeTag, pair)
    }

    fun importPackage(request: VoicePackageImportRequest): VoicePackageImportResult {
        val validationError = validateRequest(request)
        if (validationError != null) {
            return VoicePackageImportResult.Failure(validationError)
        }

        val importedRoot = File(storageRoot, IMPORTED_VOICES_DIR)
        if (!importedRoot.exists() && !importedRoot.mkdirs()) {
            return VoicePackageImportResult.Failure("Could not create local voice storage.")
        }
        if (!importedRoot.isDirectory) {
            return VoicePackageImportResult.Failure("Local voice storage path is not a directory.")
        }

        if (request.isVoicePackageBundle()) {
            return importVoicePackageBundle(request, importedRoot)
        }

        return importRawModelFile(request, importedRoot)
    }

    fun deleteStoredVoice(localModelUri: String?): Boolean {
        if (localModelUri.isNullOrBlank()) {
            return true
        }
        val uri = runCatching { URI(localModelUri.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "file") {
            return true
        }

        val storageRootFile = storageRoot.canonicalFile
        val target = runCatching { File(uri).canonicalFile }.getOrNull() ?: return false
        if (!target.toPath().startsWith(storageRootFile.toPath())) {
            return false
        }

        val deleteTarget =
            if (target.name == CUSTOM_VOICE_PACKAGE_MANIFEST_FILE && target.parentFile != null) {
                target.parentFile?.canonicalFile ?: target
            } else {
                target
            }
        if (!deleteTarget.toPath().startsWith(storageRootFile.toPath())) {
            return false
        }
        if (!deleteTarget.exists()) {
            return true
        }
        return if (deleteTarget.isDirectory) {
            deleteTarget.deleteRecursively()
        } else {
            deleteTarget.delete()
        }
    }

    private fun importPiperVoicePair(
        localeTag: String,
        pair: PiperVoicePair,
    ): VoicePackageImportResult {
        val importedRoot = File(storageRoot, IMPORTED_VOICES_DIR)
        if (!importedRoot.exists() && !importedRoot.mkdirs()) {
            return VoicePackageImportResult.Failure("Could not create local voice storage.")
        }
        if (!importedRoot.isDirectory) {
            return VoicePackageImportResult.Failure("Local voice storage path is not a directory.")
        }

        val packageDisplayName = pair.displayName.ifBlank { "Imported Piper Voice" }
        val packageDir =
            File(
                importedRoot,
                "${safePackageDirectoryName(packageDisplayName)}-${stableSuffix(pair.stableSourceKey)}",
            )
        val stagingDir = File(importedRoot, "${packageDir.name}.staging-${System.nanoTime()}")

        return try {
            if (!stagingDir.mkdirs()) {
                return VoicePackageImportResult.Failure("Could not prepare voice package storage.")
            }

            val modelDestination = stagingDir.safePackageChild(PIPER_MODEL_PACKAGE_PATH)
            val copiedModel =
                inputOpener.open(pair.model.sourceUri)?.use { input ->
                    copyInputWithSha256(
                        input = input,
                        destination = modelDestination,
                        limitBytes = MAX_IMPORTED_VOICE_BYTES,
                    )
                } ?: return VoicePackageImportResult.Failure("Could not open selected .onnx voice model.")
            if (copiedModel.sizeBytes == 0L) {
                return VoicePackageImportResult.Failure("Piper voice model is empty.")
            }

            val configDestination = stagingDir.safePackageChild(PIPER_CONFIG_PACKAGE_PATH)
            val copiedConfigBytes =
                inputOpener.open(pair.config.sourceUri)?.use { input ->
                    copyInputWithLimit(
                        input = input,
                        destination = configDestination,
                        limitBytes = MAX_CONFIG_BYTES,
                    )
                } ?: return VoicePackageImportResult.Failure("Could not open selected .onnx.json voice config.")
            if (copiedConfigBytes == 0L) {
                return VoicePackageImportResult.Failure("Piper voice config is empty.")
            }

            val parsedConfig =
                when (val result = PiperVoiceConfigParser().parse(configDestination.readText(Charsets.UTF_8))) {
                    is PiperVoiceConfigParseResult.Failure ->
                        return VoicePackageImportResult.Failure(result.message)
                    is PiperVoiceConfigParseResult.Success -> result.config
                }
            val manifest =
                CustomVoicePackageManifest(
                    packageId = safePackageId(packageDisplayName),
                    displayName = packageDisplayName,
                    localeTag = localeTag.trim(),
                    sampleText = DEFAULT_IMPORTED_VOICE_SAMPLE_TEXT,
                    runtime =
                        CustomVoiceRuntimeManifest(
                            type = CustomVoiceRuntimeType.PIPER_V1,
                            sampleRateHz = parsedConfig.sampleRateHz,
                        ),
                    model =
                        CustomVoiceModelFileManifest(
                            path = PIPER_MODEL_PACKAGE_PATH,
                            sizeBytes = copiedModel.sizeBytes,
                            sha256 = copiedModel.sha256,
                            configPath = PIPER_CONFIG_PACKAGE_PATH,
                        ),
                )
            val validation = packageValidator.validate(manifest)
            if (!validation.isValid) {
                return VoicePackageImportResult.Failure(
                    "Piper voice package is invalid: ${validation.issues.first().message}",
                )
            }
            stagingDir
                .safePackageChild(CUSTOM_VOICE_PACKAGE_MANIFEST_FILE)
                .writeText(json.encodeToString(manifest), Charsets.UTF_8)

            if (packageDir.exists() && !packageDir.deleteRecursively()) {
                return VoicePackageImportResult.Failure("Could not replace existing Piper voice package.")
            }
            if (!stagingDir.renameTo(packageDir)) {
                stagingDir.copyRecursively(packageDir, overwrite = true)
                stagingDir.deleteRecursively()
            }

            VoicePackageImportResult.Success(
                StoredVoicePackage(
                    displayName = manifest.displayName,
                    localeTag = manifest.localeTag,
                    localModelUri =
                        File(
                            packageDir,
                            CUSTOM_VOICE_PACKAGE_MANIFEST_FILE,
                        ).toURI().toString(),
                    sizeBytes = copiedModel.sizeBytes + copiedConfigBytes,
                    sampleText = manifest.sampleText,
                    runtimeType = manifest.runtime.type,
                ),
            )
        } catch (e: VoicePackageTooLargeException) {
            VoicePackageImportResult.Failure(
                "Voice file is too large. Limit is ${MAX_IMPORTED_VOICE_BYTES / BYTES_PER_MB}MB.",
            )
        } catch (e: IOException) {
            VoicePackageImportResult.Failure("Could not import Piper voice files: ${e.message}")
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        }
    }

    private fun importRawModelFile(
        request: VoicePackageImportRequest,
        importedRoot: File,
    ): VoicePackageImportResult {
        val source =
            inputOpener.open(request.sourceUri)
                ?: return VoicePackageImportResult.Failure("Could not open selected voice file.")
        val destination =
            File(
                importedRoot,
                "${safeBaseName(request.displayName)}-${stableSuffix(request.sourceUri)}.${safeExtension(request.displayName)}",
            )
        val tempFile = File.createTempFile("voice-import-", ".tmp", importedRoot)

        return try {
            val copiedBytes =
                source.use { input ->
                    copyWithLimit(
                        input = input,
                        destination = tempFile,
                    )
                }
            if (copiedBytes == 0L) {
                return VoicePackageImportResult.Failure("Voice file is empty.")
            }
            if (destination.exists() && !destination.delete()) {
                return VoicePackageImportResult.Failure("Could not replace existing voice file.")
            }
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
            VoicePackageImportResult.Success(
                StoredVoicePackage(
                    displayName = request.displayName.trim().ifBlank { "Imported Voice" },
                    localeTag = request.localeTag.trim(),
                    localModelUri = destination.toURI().toString(),
                    sizeBytes = copiedBytes,
                ),
            )
        } catch (e: VoicePackageTooLargeException) {
            VoicePackageImportResult.Failure(
                "Voice file is too large. Limit is ${MAX_IMPORTED_VOICE_BYTES / BYTES_PER_MB}MB.",
            )
        } catch (e: java.io.IOException) {
            VoicePackageImportResult.Failure("Could not copy voice file: ${e.message}")
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun importVoicePackageBundle(
        request: VoicePackageImportRequest,
        importedRoot: File,
    ): VoicePackageImportResult {
        val source =
            inputOpener.open(request.sourceUri)
                ?: return VoicePackageImportResult.Failure("Could not open selected voice package.")
        val tempPackage = File.createTempFile("voice-package-", ".zip", importedRoot)
        var stagingDir: File? = null

        return try {
            val packageBytes =
                source.use { input ->
                    copyWithLimit(
                        input = input,
                        destination = tempPackage,
                    )
                }
            if (packageBytes == 0L) {
                return VoicePackageImportResult.Failure("Voice package is empty.")
            }

            ZipFile(tempPackage).use { zip ->
                val manifestText = zip.readManifestText()
                val manifest = decodeManifest(manifestText)
                val validation = packageValidator.validate(manifest)
                if (!validation.isValid) {
                    return VoicePackageImportResult.Failure(
                        "Voice package is invalid: ${validation.issues.first().message}",
                    )
                }

                val modelEntry =
                    zip.getRequiredEntry(manifest.model.path)
                        ?: return VoicePackageImportResult.Failure(
                            "Voice package is missing the declared model file.",
                        )
                val packageDir =
                    File(
                        importedRoot,
                        "${safePackageDirectoryName(manifest.packageId)}-${stableSuffix(request.sourceUri)}",
                    )
                stagingDir =
                    File(
                        importedRoot,
                        "${packageDir.name}.staging-${System.nanoTime()}",
                    )
                val staging = requireNotNull(stagingDir)
                if (!staging.mkdirs()) {
                    return VoicePackageImportResult.Failure("Could not prepare voice package storage.")
                }

                val manifestDestination = staging.safePackageChild(CUSTOM_VOICE_PACKAGE_MANIFEST_FILE)
                manifestDestination.parentFile?.mkdirs()
                manifestDestination.writeText(manifestText, Charsets.UTF_8)

                val modelDestination = staging.safePackageChild(manifest.model.path)
                val copiedModel = zip.copyEntryWithSha256(modelEntry, modelDestination)
                if (copiedModel.sizeBytes != manifest.model.sizeBytes) {
                    return VoicePackageImportResult.Failure("Voice package model size does not match manifest.")
                }
                if (!copiedModel.sha256.equals(manifest.model.sha256, ignoreCase = true)) {
                    return VoicePackageImportResult.Failure("Voice package model checksum does not match manifest.")
                }

                val configPath = manifest.model.configPath
                if (configPath != null) {
                    val configEntry =
                        zip.getRequiredEntry(configPath)
                            ?: return VoicePackageImportResult.Failure(
                                "Voice package is missing the declared config file.",
                            )
                    zip.copyEntryWithLimit(
                        entry = configEntry,
                        destination = staging.safePackageChild(configPath),
                        limitBytes = MAX_CONFIG_BYTES,
                    )
                }

                val phonemizer = manifest.phonemizer
                if (phonemizer != null) {
                    val phonemizerEntry =
                        zip.getRequiredEntry(phonemizer.path)
                            ?: return VoicePackageImportResult.Failure(
                                "Voice package is missing the declared phonemizer file.",
                            )
                    val copiedPhonemizer =
                        zip.copyEntryWithSha256(
                            entry = phonemizerEntry,
                            destination = staging.safePackageChild(phonemizer.path),
                            limitBytes = MAX_PHONEMIZER_BYTES,
                        )
                    if (copiedPhonemizer.sizeBytes != phonemizer.sizeBytes) {
                        return VoicePackageImportResult.Failure(
                            "Voice package phonemizer size does not match manifest.",
                        )
                    }
                    if (!copiedPhonemizer.sha256.equals(phonemizer.sha256, ignoreCase = true)) {
                        return VoicePackageImportResult.Failure(
                            "Voice package phonemizer checksum does not match manifest.",
                        )
                    }
                }

                if (packageDir.exists() && !packageDir.deleteRecursively()) {
                    return VoicePackageImportResult.Failure("Could not replace existing voice package.")
                }
                if (!staging.renameTo(packageDir)) {
                    staging.copyRecursively(packageDir, overwrite = true)
                    staging.deleteRecursively()
                }

                VoicePackageImportResult.Success(
                    StoredVoicePackage(
                        displayName = manifest.displayName.trim(),
                        localeTag = manifest.localeTag.trim(),
                        localModelUri =
                            File(
                                packageDir,
                                CUSTOM_VOICE_PACKAGE_MANIFEST_FILE,
                            ).toURI().toString(),
                        sizeBytes = copiedModel.sizeBytes,
                        sampleText = manifest.sampleText.trim(),
                        runtimeType = manifest.runtime.type.trim(),
                    ),
                )
            }
        } catch (e: VoicePackageTooLargeException) {
            VoicePackageImportResult.Failure(
                "Voice package is too large. Limit is ${MAX_IMPORTED_VOICE_BYTES / BYTES_PER_MB}MB.",
            )
        } catch (e: SerializationException) {
            VoicePackageImportResult.Failure("Voice package manifest could not be read.")
        } catch (e: IOException) {
            VoicePackageImportResult.Failure("Could not import voice package: ${e.message}")
        } finally {
            if (tempPackage.exists()) {
                tempPackage.delete()
            }
            stagingDir?.takeIf { it.exists() }?.deleteRecursively()
        }
    }

    private fun validateRequest(request: VoicePackageImportRequest): String? {
        if (request.displayName.isBlank()) return "Voice display name is required."
        if (!request.localeTag.trim().startsWith("en", ignoreCase = true)) {
            return "Only English voice imports are supported first."
        }
        if (request.sourceUri.isBlank()) return "Voice file URI is required."
        if (!request.sourceUri.isLocalImportUri()) {
            return "Voice imports must come from a local file."
        }
        if (request.declaredSizeBytes < 0L) return "Voice file size is invalid."
        if (request.declaredSizeBytes > MAX_IMPORTED_VOICE_BYTES) {
            return "Voice file is too large. Limit is ${MAX_IMPORTED_VOICE_BYTES / BYTES_PER_MB}MB."
        }
        return null
    }

    private fun copyWithLimit(
        input: InputStream,
        destination: File,
    ): Long {
        var copiedBytes = 0L
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                copiedBytes += read
                if (copiedBytes > MAX_IMPORTED_VOICE_BYTES) {
                    throw VoicePackageTooLargeException()
                }
                output.write(buffer, 0, read)
            }
        }
        return copiedBytes
    }

    private fun copyInputWithLimit(
        input: InputStream,
        destination: File,
        limitBytes: Long,
    ): Long {
        var copiedBytes = 0L
        destination.parentFile?.mkdirs()
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                copiedBytes += read
                if (copiedBytes > limitBytes) {
                    throw VoicePackageTooLargeException()
                }
                output.write(buffer, 0, read)
            }
        }
        return copiedBytes
    }

    private fun copyInputWithSha256(
        input: InputStream,
        destination: File,
        limitBytes: Long,
    ): CopiedPackageEntry {
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        destination.parentFile?.mkdirs()
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                copiedBytes += read
                if (copiedBytes > limitBytes) {
                    throw VoicePackageTooLargeException()
                }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        return CopiedPackageEntry(
            sizeBytes = copiedBytes,
            sha256 = digest.digest().toHexString(),
        )
    }

    private fun String.isLocalImportUri(): Boolean =
        runCatching {
            URI(trim()).scheme?.lowercase()
        }.getOrNull().let { scheme ->
            scheme == "content" || scheme == "file"
        }

    private fun VoicePackageImportRequest.isVoicePackageBundle(): Boolean =
        VOICE_PACKAGE_EXTENSIONS.any { extension ->
            displayName.hasPackageExtension(extension) || sourceUri.hasPackageExtension(extension)
        }

    private fun VoicePackageImportFile.isVoicePackageBundle(): Boolean =
        VOICE_PACKAGE_EXTENSIONS.any { extension ->
            displayName.hasPackageExtension(extension) || sourceUri.hasPackageExtension(extension)
        }

    private fun validateSelectedFile(file: VoicePackageImportFile): String? {
        if (file.displayName.isBlank()) return "Voice file name is required."
        if (file.sourceUri.isBlank()) return "Voice file URI is required."
        if (!file.sourceUri.isLocalImportUri()) {
            return "Voice imports must come from local files."
        }
        if (file.declaredSizeBytes < 0L) return "Voice file size is invalid."
        if (file.declaredSizeBytes > MAX_IMPORTED_VOICE_BYTES) {
            return "Voice file is too large. Limit is ${MAX_IMPORTED_VOICE_BYTES / BYTES_PER_MB}MB."
        }
        return null
    }

    private fun findPiperVoicePair(files: List<VoicePackageImportFile>): PiperVoicePair? {
        val modelFiles = files.filter { file -> file.piperModelBaseName() != null }
        val configFiles = files.filter { file -> file.piperConfigBaseName() != null }
        if (modelFiles.size != 1 || configFiles.size != 1) return null

        val model = modelFiles.single()
        val config = configFiles.single()
        val modelBase = model.piperModelBaseName() ?: return null
        val configBase = config.piperConfigBaseName() ?: return null
        if (!modelBase.equals(configBase, ignoreCase = true)) return null

        return PiperVoicePair(
            model = model,
            config = config,
            displayName = readableVoiceName(modelBase),
        )
    }

    private fun VoicePackageImportFile.piperModelBaseName(): String? {
        val name = importFileName()
        if (!name.endsWith(PIPER_MODEL_EXTENSION, ignoreCase = true)) return null
        if (name.endsWith(PIPER_CONFIG_EXTENSION, ignoreCase = true)) return null
        return name.dropLast(PIPER_MODEL_EXTENSION.length).ifBlank { null }
    }

    private fun VoicePackageImportFile.piperConfigBaseName(): String? {
        val name = importFileName()
        if (!name.endsWith(PIPER_CONFIG_EXTENSION, ignoreCase = true)) return null
        return name.dropLast(PIPER_CONFIG_EXTENSION.length).ifBlank { null }
    }

    private fun VoicePackageImportFile.importFileName(): String =
        displayName
            .ifBlank { sourceUri }
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .trim()

    private fun readableVoiceName(baseName: String): String =
        baseName
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Imported Piper Voice" }

    private fun safePackageId(raw: String): String {
        val id =
            raw
                .lowercase()
                .replace(Regex("[^a-z0-9._-]+"), ".")
                .trim('.', '_', '-')
                .take(MAX_PACKAGE_ID_LENGTH)
        return id.ifBlank { "imported.piper.voice" }
    }

    private fun String.hasPackageExtension(extension: String): Boolean =
        trim()
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()
            .endsWith(extension)

    private fun safeBaseName(raw: String): String =
        raw
            .substringBeforeLast('.', raw)
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_BASE_NAME_LENGTH)
            .ifBlank { "voice" }

    private fun safeExtension(raw: String): String {
        val extension =
            raw
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase()
                .replace(Regex("[^a-z0-9]"), "")
                .take(MAX_EXTENSION_LENGTH)
        return extension.ifBlank { "voice" }
    }

    private fun safePackageDirectoryName(raw: String): String =
        raw
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_BASE_NAME_LENGTH)
            .ifBlank { "voice-package" }

    private fun stableSuffix(sourceUri: String): String =
        Integer.toHexString(sourceUri.hashCode())

    private fun ZipFile.readManifestText(): String {
        val entry =
            getRequiredEntry(CUSTOM_VOICE_PACKAGE_MANIFEST_FILE)
                ?: throw IOException("Missing $CUSTOM_VOICE_PACKAGE_MANIFEST_FILE.")
        val output = ByteArrayOutputStream()
        var copiedBytes = 0L
        getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                copiedBytes += read
                if (copiedBytes > MAX_MANIFEST_BYTES) {
                    throw VoicePackageTooLargeException()
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun decodeManifest(raw: String): CustomVoicePackageManifest =
        json.decodeFromString<CustomVoicePackageManifest>(raw)

    private fun ZipFile.getRequiredEntry(path: String): ZipEntry? =
        getEntry(path.normalizedPackagePath())?.takeIf { entry -> !entry.isDirectory }

    private fun ZipFile.copyEntryWithSha256(
        entry: ZipEntry,
        destination: File,
        limitBytes: Long = MAX_IMPORTED_VOICE_BYTES,
    ): CopiedPackageEntry {
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        destination.parentFile?.mkdirs()
        getInputStream(entry).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    copiedBytes += read
                    if (copiedBytes > limitBytes) {
                        throw VoicePackageTooLargeException()
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        return CopiedPackageEntry(
            sizeBytes = copiedBytes,
            sha256 = digest.digest().toHexString(),
        )
    }

    private fun ZipFile.copyEntryWithLimit(
        entry: ZipEntry,
        destination: File,
        limitBytes: Long,
    ) {
        var copiedBytes = 0L
        destination.parentFile?.mkdirs()
        getInputStream(entry).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    copiedBytes += read
                    if (copiedBytes > limitBytes) {
                        throw VoicePackageTooLargeException()
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun File.safePackageChild(packageRelativePath: String): File {
        val child = File(this, packageRelativePath.normalizedPackagePath())
        val rootPath = canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        if (!childPath.startsWith(rootPath)) {
            throw IOException("Unsafe package path.")
        }
        return child
    }

    private fun String.normalizedPackagePath(): String =
        trim().replace('\\', '/')

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class CopiedPackageEntry(
        val sizeBytes: Long,
        val sha256: String,
    )

    private data class PiperVoicePair(
        val model: VoicePackageImportFile,
        val config: VoicePackageImportFile,
        val displayName: String,
    ) {
        val stableSourceKey: String
            get() = "${model.sourceUri}|${config.sourceUri}"
    }

    private class VoicePackageTooLargeException : RuntimeException()

    private companion object {
        private const val IMPORTED_VOICES_DIR = "imported-voices"
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 64L * 1024L
        private const val MAX_CONFIG_BYTES = 8L * 1024L * 1024L
        private const val MAX_PHONEMIZER_BYTES = 32L * 1024L * 1024L
        private const val MAX_BASE_NAME_LENGTH = 48
        private const val MAX_PACKAGE_ID_LENGTH = 64
        private const val MAX_EXTENSION_LENGTH = 12
        private const val MAX_IMPORTED_VOICE_BYTES = 512L * BYTES_PER_MB
        private const val PIPER_PAIR_FILE_COUNT = 2
        private const val PIPER_MODEL_EXTENSION = ".onnx"
        private const val PIPER_CONFIG_EXTENSION = ".onnx.json"
        private const val PIPER_MODEL_PACKAGE_PATH = "models/model.onnx"
        private const val PIPER_CONFIG_PACKAGE_PATH = "models/model.onnx.json"
        private val VOICE_PACKAGE_EXTENSIONS = listOf(".voicepkg", ".zip")
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}

class AndroidLocalVoiceStorage(context: Context) :
    LocalVoiceStorage(
        storageRoot = File(context.applicationContext.filesDir, "voice-models"),
        inputOpener = AndroidVoicePackageInputOpener(context.applicationContext),
    )

private object FileVoicePackageInputOpener : VoicePackageInputOpener {
    override fun open(sourceUri: String): InputStream? {
        val uri = runCatching { URI(sourceUri) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "file") return null
        return runCatching { File(uri).inputStream() }.getOrNull()
    }
}

private class AndroidVoicePackageInputOpener(
    private val context: Context,
) : VoicePackageInputOpener {
    override fun open(sourceUri: String): InputStream? {
        val uri = runCatching { android.net.Uri.parse(sourceUri) }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)
        }.getOrNull()
    }
}
