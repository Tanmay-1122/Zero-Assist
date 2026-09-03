/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.SharedPreferences
import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import com.zeroclaw.android.model.VoiceModelStatus
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Catalog for offline assistant voices.
 *
 * The repository deliberately ships with no installed voice. Voice assets are
 * expected to be downloaded or imported before the assistant can speak.
 */
class LocalVoiceCatalogRepository(
    initialVoices: List<VoiceModel> = defaultCatalog(),
    private val store: LocalVoiceCatalogStore = InMemoryLocalVoiceCatalogStore(),
) {
    private val initialState = mergeStoredState(initialVoices, store.load())
    private val _voices = MutableStateFlow(initialState.voices)
    val voices: StateFlow<List<VoiceModel>> = _voices.asStateFlow()

    private val _selectedVoiceId =
        MutableStateFlow(
            initialState.selectedVoiceId,
        )
    val selectedVoiceId: StateFlow<String?> = _selectedVoiceId.asStateFlow()

    fun selectVoice(voiceId: String): Boolean {
        val voice = _voices.value.firstOrNull { it.id == voiceId }
        if (voice?.status != VoiceModelStatus.Installed || !voice.isEnglish) {
            return false
        }
        _selectedVoiceId.value = voiceId
        persist()
        return true
    }

    fun startDownload(voiceId: String): Boolean {
        var updated = false
        _voices.update { current ->
            current.map { voice ->
                if (voice.id == voiceId && voice.status != VoiceModelStatus.Installed) {
                    updated = true
                    voice.copy(
                        status =
                            downloadingStatus(
                                bytesDownloaded = 0L,
                                totalBytes = voice.sizeBytes,
                            ),
                    )
                } else {
                    voice
                }
            }
        }
        if (updated) {
            persist()
        }
        return updated
    }

    fun updateDownloadProgress(
        voiceId: String,
        bytesDownloaded: Long,
        totalBytes: Long? = null,
    ): Boolean {
        var updated = false
        _voices.update { current ->
            current.map { voice ->
                if (voice.id == voiceId && voice.status is VoiceModelStatus.Downloading) {
                    updated = true
                    voice.copy(
                        status =
                            downloadingStatus(
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes ?: voice.sizeBytes,
                            ),
                    )
                } else {
                    voice
                }
            }
        }
        if (updated) {
            persist()
        }
        return updated
    }

    fun markDownloadFailed(
        voiceId: String,
        reason: String,
    ): Boolean {
        var updated = false
        _voices.update { current ->
            current.map { voice ->
                if (voice.id == voiceId && voice.status is VoiceModelStatus.Downloading) {
                    updated = true
                    voice.copy(
                        status = VoiceModelStatus.Failed(reason.ifBlank { "Download failed." }),
                    )
                } else {
                    voice
                }
            }
        }
        if (updated) {
            persist()
        }
        return updated
    }

    fun markInstalled(
        voiceId: String,
        modelUri: String? = null,
    ): Boolean {
        var updated = false
        _voices.update { current ->
            current.map { voice ->
                if (voice.id == voiceId && voice.isEnglish) {
                    updated = true
                    voice.copy(
                        status = VoiceModelStatus.Installed,
                        modelUri = modelUri ?: voice.modelUri,
                    )
                } else {
                    voice
                }
            }
        }
        if (updated) {
            _selectedVoiceId.value = voiceId
            persist()
        }
        return updated
    }

    fun importInstalledVoice(voice: VoiceModel): Boolean {
        if (!voice.isValidImportedVoice()) {
            return false
        }
        val installedVoice =
            voice.copy(
                id = voice.id.trim(),
                displayName = voice.displayName.trim(),
                localeTag = voice.localeTag.trim(),
                source = VoiceModelSource.IMPORTED,
                status = VoiceModelStatus.Installed,
                sampleText = voice.sampleText.trim(),
                modelUri = voice.modelUri?.trim(),
            )
        _voices.update { current ->
            current.filterNot { it.id == installedVoice.id } + installedVoice
        }
        _selectedVoiceId.value = installedVoice.id
        persist()
        return true
    }

    fun deleteVoice(voiceId: String): VoiceModel? {
        val existing =
            _voices.value.firstOrNull { voice ->
                voice.id == voiceId && voice.status == VoiceModelStatus.Installed
            } ?: return null

        _voices.update { current ->
            current.mapNotNull { voice ->
                if (voice.id != voiceId) {
                    voice
                } else {
                    when (voice.source) {
                        VoiceModelSource.CATALOG ->
                            voice.copy(
                                status = VoiceModelStatus.AvailableForDownload,
                                modelUri = null,
                            )
                        VoiceModelSource.IMPORTED -> null
                    }
                }
            }
        }
        if (_selectedVoiceId.value == voiceId) {
            _selectedVoiceId.value =
                _voices.value.firstOrNull { voice ->
                    voice.status == VoiceModelStatus.Installed && voice.isEnglish
                }?.id
        }
        persist()
        return existing
    }

    fun selectedVoice(): VoiceModel? =
        _voices.value.firstOrNull { it.id == _selectedVoiceId.value }

    fun voice(voiceId: String): VoiceModel? =
        _voices.value.firstOrNull { it.id == voiceId }

    private fun persist() {
        store.save(
            LocalVoiceCatalogSnapshot(
                voices = _voices.value,
                selectedVoiceId = _selectedVoiceId.value,
            ),
        )
    }

    companion object {
        fun defaultCatalog(): List<VoiceModel> =
            listOf(
                VoiceModel(
                    id = "en-calm-guide",
                    displayName = "Calm Guide",
                    toneLabel = "Calm",
                    localeTag = "en-US",
                    description = "Soft, steady assistant voice for short replies.",
                    sizeBytes = 63L * 1024L * 1024L,
                    source = VoiceModelSource.CATALOG,
                    status = VoiceModelStatus.AvailableForDownload,
                    sampleText = "I am ready when you are.",
                    // en_US-lessac-medium — natural American English, ~63 MB
                    downloadUri = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
                    packageSha256 = null,
                ),
                VoiceModel(
                    id = "en-clear-operator",
                    displayName = "Clear Operator",
                    toneLabel = "Clear",
                    localeTag = "en-US",
                    description = "Crisp voice for calls, reminders, and action confirmations.",
                    sizeBytes = 117L * 1024L * 1024L,
                    source = VoiceModelSource.CATALOG,
                    status = VoiceModelStatus.AvailableForDownload,
                    sampleText = "Task received. I will handle it locally.",
                    // en_US-ryan-high — clear, expressive American English, ~117 MB
                    downloadUri = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/high/en_US-ryan-high.onnx",
                    packageSha256 = null,
                ),
                VoiceModel(
                    id = "en-warm-companion",
                    displayName = "Warm Companion",
                    toneLabel = "Warm",
                    localeTag = "en-GB",
                    description = "Friendly voice for longer assistant responses.",
                    sizeBytes = 63L * 1024L * 1024L,
                    source = VoiceModelSource.CATALOG,
                    status = VoiceModelStatus.AvailableForDownload,
                    sampleText = "Here is what I found on your phone.",
                    // en_GB-alan-medium — warm British English, ~63 MB
                    downloadUri = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx",
                    packageSha256 = null,
                ),
            )

        private fun mergeStoredState(
            defaultVoices: List<VoiceModel>,
            storedSnapshot: LocalVoiceCatalogSnapshot?,
        ): LocalVoiceCatalogSnapshot {
            if (storedSnapshot == null) {
                return LocalVoiceCatalogSnapshot(
                    voices = defaultVoices,
                    selectedVoiceId =
                        defaultVoices.firstOrNull { voice ->
                            voice.status == VoiceModelStatus.Installed && voice.isEnglish
                        }?.id,
                )
            }
            val storedById = storedSnapshot.voices.associateBy { it.id }
        val mergedCatalog =
                defaultVoices.map { defaultVoice ->
                    storedById[defaultVoice.id]?.withCatalogDownloadMetadata(defaultVoice)
                        ?: defaultVoice
                }
            val importedVoices =
                storedSnapshot.voices.filter { voice ->
                    voice.source == VoiceModelSource.IMPORTED && voice.isEnglish
                }
            val voices = (mergedCatalog + importedVoices).distinctBy { it.id }
            val selectedId =
                storedSnapshot.selectedVoiceId?.takeIf { id ->
                    voices.any { voice ->
                        voice.id == id &&
                            voice.isEnglish &&
                            voice.status == VoiceModelStatus.Installed
                    }
                }
            return LocalVoiceCatalogSnapshot(
                voices = voices,
                selectedVoiceId = selectedId,
            )
        }
    }
}

private fun VoiceModel.isValidImportedVoice(): Boolean =
    id.isNotBlank() &&
        displayName.isNotBlank() &&
        sampleText.isNotBlank() &&
        sizeBytes >= 0L &&
        localeTag.trim().startsWith("en", ignoreCase = true) &&
        modelUri?.trim()?.isLocalVoiceModelUri() == true

private fun String.isLocalVoiceModelUri(): Boolean =
    runCatching {
        URI(trim()).scheme?.lowercase()
    }.getOrNull().let { scheme ->
        scheme == "content" || scheme == "file"
    }

data class LocalVoiceCatalogSnapshot(
    val voices: List<VoiceModel>,
    val selectedVoiceId: String?,
)

interface LocalVoiceCatalogStore {
    fun load(): LocalVoiceCatalogSnapshot?

    fun save(snapshot: LocalVoiceCatalogSnapshot)
}

class InMemoryLocalVoiceCatalogStore : LocalVoiceCatalogStore {
    private var snapshot: LocalVoiceCatalogSnapshot? = null

    override fun load(): LocalVoiceCatalogSnapshot? = snapshot

    override fun save(snapshot: LocalVoiceCatalogSnapshot) {
        this.snapshot = snapshot
    }
}

class SharedPreferencesLocalVoiceCatalogStore(
    context: Context,
) : LocalVoiceCatalogStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): LocalVoiceCatalogSnapshot? {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            val stored = json.decodeFromString<StoredLocalVoiceCatalogSnapshot>(raw)
            stored.toSnapshot()
        }.getOrNull()
    }

    override fun save(snapshot: LocalVoiceCatalogSnapshot) {
        preferences
            .edit()
            .putString(KEY_SNAPSHOT, json.encodeToString(snapshot.toStored()))
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "local_voice_catalog"
        private const val KEY_SNAPSHOT = "snapshot"
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}

@Serializable
private data class StoredLocalVoiceCatalogSnapshot(
    val voices: List<StoredVoiceModel>,
    val selectedVoiceId: String? = null,
) {
    fun toSnapshot(): LocalVoiceCatalogSnapshot =
        LocalVoiceCatalogSnapshot(
            voices = voices.map { it.toVoiceModel() },
            selectedVoiceId = selectedVoiceId,
        )
}

@Serializable
private data class StoredVoiceModel(
    val id: String,
    val displayName: String,
    val toneLabel: String,
    val localeTag: String,
    val description: String,
    val sizeBytes: Long,
    val source: VoiceModelSource,
    val status: StoredVoiceStatus,
    val sampleText: String,
    val modelUri: String? = null,
    val downloadUri: String? = null,
    val packageSha256: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val failureReason: String? = null,
) {
    fun toVoiceModel(): VoiceModel =
        VoiceModel(
            id = id,
            displayName = displayName,
            toneLabel = toneLabel,
            localeTag = localeTag,
            description = description,
            sizeBytes = sizeBytes,
            source = source,
            status =
                when (status) {
                    StoredVoiceStatus.AvailableForDownload ->
                        VoiceModelStatus.AvailableForDownload
                    StoredVoiceStatus.Downloading ->
                        downloadingStatus(bytesDownloaded, totalBytes)
                    StoredVoiceStatus.Installed ->
                        VoiceModelStatus.Installed
                    StoredVoiceStatus.Failed ->
                        VoiceModelStatus.Failed(
                            failureReason?.ifBlank { null } ?: "Download failed.",
                        )
                },
            sampleText = sampleText,
            modelUri = modelUri,
            downloadUri = downloadUri,
            packageSha256 = packageSha256,
        )
}

@Serializable
private enum class StoredVoiceStatus {
    AvailableForDownload,
    Downloading,
    Installed,
    Failed,
}

private fun LocalVoiceCatalogSnapshot.toStored(): StoredLocalVoiceCatalogSnapshot =
    StoredLocalVoiceCatalogSnapshot(
        voices = voices.map { it.toStored() },
        selectedVoiceId = selectedVoiceId,
    )

private fun VoiceModel.toStored(): StoredVoiceModel =
    when (val currentStatus = status) {
        VoiceModelStatus.AvailableForDownload ->
            baseStoredVoice(status = StoredVoiceStatus.AvailableForDownload)
        is VoiceModelStatus.Downloading ->
            baseStoredVoice(
                status = StoredVoiceStatus.Downloading,
                bytesDownloaded = currentStatus.bytesDownloaded,
                totalBytes = currentStatus.totalBytes,
            )
        is VoiceModelStatus.Failed ->
            baseStoredVoice(
                status = StoredVoiceStatus.Failed,
                failureReason = currentStatus.reason,
            )
        VoiceModelStatus.Installed ->
            baseStoredVoice(status = StoredVoiceStatus.Installed)
    }

private fun VoiceModel.baseStoredVoice(
    status: StoredVoiceStatus,
    bytesDownloaded: Long = 0L,
    totalBytes: Long? = null,
    failureReason: String? = null,
): StoredVoiceModel =
    StoredVoiceModel(
        id = id,
        displayName = displayName,
        toneLabel = toneLabel,
        localeTag = localeTag,
        description = description,
        sizeBytes = sizeBytes,
        source = source,
        status = status,
        sampleText = sampleText,
        modelUri = modelUri,
        downloadUri = downloadUri,
        packageSha256 = packageSha256,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        failureReason = failureReason,
    )

private fun VoiceModel.withCatalogDownloadMetadata(defaultVoice: VoiceModel): VoiceModel =
    if (source == VoiceModelSource.CATALOG) {
        copy(
            downloadUri = defaultVoice.downloadUri,
            packageSha256 = defaultVoice.packageSha256,
        )
    } else {
        this
    }

private fun downloadingStatus(
    bytesDownloaded: Long,
    totalBytes: Long?,
): VoiceModelStatus.Downloading {
    val normalizedTotal = totalBytes?.takeIf { it > 0L }
    val normalizedBytes =
        if (normalizedTotal == null) {
            bytesDownloaded.coerceAtLeast(0L)
        } else {
            bytesDownloaded.coerceIn(0L, normalizedTotal)
        }
    return VoiceModelStatus.Downloading(
        bytesDownloaded = normalizedBytes,
        totalBytes = normalizedTotal,
    )
}
