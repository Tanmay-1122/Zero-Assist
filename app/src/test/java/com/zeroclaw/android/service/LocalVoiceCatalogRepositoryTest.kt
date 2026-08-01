/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import com.zeroclaw.android.model.VoiceModelStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LocalVoiceCatalogRepository")
class LocalVoiceCatalogRepositoryTest {
    @Test
    fun `default catalog has no installed or selected voice`() {
        val repository = LocalVoiceCatalogRepository()

        assertNull(repository.selectedVoiceId.value)
        assertTrue(
            repository.voices.value.all { voice ->
                voice.status == VoiceModelStatus.AvailableForDownload && voice.isEnglish
            },
        )
    }

    @Test
    fun `downloadable voice cannot be selected before install`() {
        val repository = LocalVoiceCatalogRepository()

        assertFalse(repository.selectVoice("en-calm-guide"))
        assertNull(repository.selectedVoiceId.value)
    }

    @Test
    fun `startDownload marks voice as downloading`() {
        val repository = LocalVoiceCatalogRepository()

        assertTrue(repository.startDownload("en-calm-guide"))

        val status = repository.voices.value.first { it.id == "en-calm-guide" }.status
        assertTrue(status is VoiceModelStatus.Downloading)
    }

    @Test
    fun `download progress survives repository recreation`() {
        val store = InMemoryLocalVoiceCatalogStore()
        val firstRepository = LocalVoiceCatalogRepository(store = store)

        assertTrue(firstRepository.startDownload("en-calm-guide"))
        assertTrue(
            firstRepository.updateDownloadProgress(
                voiceId = "en-calm-guide",
                bytesDownloaded = 24L,
                totalBytes = 48L,
            ),
        )

        val secondRepository = LocalVoiceCatalogRepository(store = store)
        val status = secondRepository.voices.value.first { it.id == "en-calm-guide" }.status

        assertTrue(status is VoiceModelStatus.Downloading)
        status as VoiceModelStatus.Downloading
        assertEquals(24L, status.bytesDownloaded)
        assertEquals(48L, status.totalBytes)
        assertNull(secondRepository.selectedVoiceId.value)
    }

    @Test
    fun `download failure survives repository recreation`() {
        val store = InMemoryLocalVoiceCatalogStore()
        val firstRepository = LocalVoiceCatalogRepository(store = store)

        assertTrue(firstRepository.startDownload("en-calm-guide"))
        assertTrue(
            firstRepository.markDownloadFailed(
                voiceId = "en-calm-guide",
                reason = "Checksum mismatch.",
            ),
        )

        val secondRepository = LocalVoiceCatalogRepository(store = store)
        val status = secondRepository.voices.value.first { it.id == "en-calm-guide" }.status

        assertEquals(VoiceModelStatus.Failed("Checksum mismatch."), status)
        assertNull(secondRepository.selectedVoiceId.value)
    }

    @Test
    fun `markInstalled selects installed English voice`() {
        val repository = LocalVoiceCatalogRepository()

        assertTrue(repository.markInstalled("en-calm-guide", modelUri = "file:///voices/calm.onnx"))

        val voice = repository.voices.value.first { it.id == "en-calm-guide" }
        assertEquals(VoiceModelStatus.Installed, voice.status)
        assertEquals("en-calm-guide", repository.selectedVoiceId.value)
    }

    @Test
    fun `importInstalledVoice accepts English imported model and selects it`() {
        val repository = LocalVoiceCatalogRepository()

        val imported =
            VoiceModel(
                id = "imported-clear",
                displayName = "Imported Clear",
                toneLabel = "Clear",
                localeTag = "en-US",
                description = "Imported local model.",
                sizeBytes = 64L,
                source = VoiceModelSource.CATALOG,
                status = VoiceModelStatus.AvailableForDownload,
                sampleText = "Imported voice preview.",
                modelUri = "content://voice/imported-clear",
            )

        assertTrue(repository.importInstalledVoice(imported))

        val voice = repository.voices.value.first { it.id == "imported-clear" }
        assertEquals(VoiceModelSource.IMPORTED, voice.source)
        assertEquals(VoiceModelStatus.Installed, voice.status)
        assertEquals("imported-clear", repository.selectedVoiceId.value)
    }

    @Test
    fun `importInstalledVoice rejects non English model`() {
        val repository = LocalVoiceCatalogRepository()

        val imported =
            VoiceModel(
                id = "imported-hindi",
                displayName = "Imported Hindi",
                toneLabel = "Warm",
                localeTag = "hi-IN",
                description = "Imported local model.",
                sizeBytes = 64L,
                source = VoiceModelSource.IMPORTED,
                status = VoiceModelStatus.Installed,
                sampleText = "Namaste.",
                modelUri = "content://voice/imported-hindi",
            )

        assertFalse(repository.importInstalledVoice(imported))
        assertNull(repository.selectedVoiceId.value)
    }

    @Test
    fun `importInstalledVoice rejects missing local model uri`() {
        val repository = LocalVoiceCatalogRepository()

        assertFalse(repository.importInstalledVoice(importedVoice(modelUri = null)))
        assertFalse(repository.voices.value.any { it.id == "imported-ready" })
        assertNull(repository.selectedVoiceId.value)
    }

    @Test
    fun `importInstalledVoice rejects network model uri`() {
        val repository = LocalVoiceCatalogRepository()

        assertFalse(
            repository.importInstalledVoice(
                importedVoice(modelUri = "https://voices.example.com/imported-ready.onnx"),
            ),
        )
        assertFalse(repository.voices.value.any { it.id == "imported-ready" })
        assertNull(repository.selectedVoiceId.value)
    }

    @Test
    fun `failed import does not replace existing selected voice`() {
        val repository = LocalVoiceCatalogRepository()
        assertTrue(repository.importInstalledVoice(importedVoice()))

        assertFalse(
            repository.importInstalledVoice(
                importedVoice(
                    displayName = "Replacement",
                    modelUri = "https://voices.example.com/replacement.onnx",
                ),
            ),
        )

        val voice = repository.voices.value.first { it.id == "imported-ready" }
        assertEquals("Imported Ready", voice.displayName)
        assertEquals("content://voice/imported-ready", voice.modelUri)
        assertEquals("imported-ready", repository.selectedVoiceId.value)
    }

    @Test
    fun `installed catalog voice survives repository recreation`() {
        val store = InMemoryLocalVoiceCatalogStore()
        val firstRepository = LocalVoiceCatalogRepository(store = store)

        assertTrue(
            firstRepository.markInstalled(
                "en-clear-operator",
                modelUri = "file:///voices/clear.onnx",
            ),
        )

        val secondRepository = LocalVoiceCatalogRepository(store = store)
        val voice = secondRepository.voices.value.first { it.id == "en-clear-operator" }

        assertEquals(VoiceModelStatus.Installed, voice.status)
        assertEquals("file:///voices/clear.onnx", voice.modelUri)
        assertEquals("en-clear-operator", secondRepository.selectedVoiceId.value)
    }

    @Test
    fun `deleteVoice removes imported voice and clears selection`() {
        val repository = LocalVoiceCatalogRepository()
        assertTrue(repository.importInstalledVoice(importedVoice()))

        val deletedVoice = repository.deleteVoice("imported-ready")

        assertEquals("imported-ready", deletedVoice?.id)
        assertFalse(repository.voices.value.any { it.id == "imported-ready" })
        assertNull(repository.selectedVoiceId.value)
    }

    @Test
    fun `deleteVoice resets installed catalog voice to downloadable`() {
        val repository = LocalVoiceCatalogRepository()
        assertTrue(repository.markInstalled("en-calm-guide", modelUri = "file:///voices/calm.voicepkg"))

        val deletedVoice = repository.deleteVoice("en-calm-guide")

        val voice = repository.voices.value.first { it.id == "en-calm-guide" }
        assertEquals("en-calm-guide", deletedVoice?.id)
        assertEquals(VoiceModelStatus.AvailableForDownload, voice.status)
        assertNull(voice.modelUri)
        assertNull(repository.selectedVoiceId.value)
    }

    @Test
    fun `imported voice survives repository recreation`() {
        val store = InMemoryLocalVoiceCatalogStore()
        val firstRepository = LocalVoiceCatalogRepository(store = store)
        val imported =
            VoiceModel(
                id = "imported-ready",
                displayName = "Imported Ready",
                toneLabel = "Custom",
                localeTag = "en-US",
                description = "Ready-made local model.",
                sizeBytes = 128L,
                source = VoiceModelSource.IMPORTED,
                status = VoiceModelStatus.Installed,
                sampleText = "Ready.",
                modelUri = "content://voice/imported-ready",
            )

        assertTrue(firstRepository.importInstalledVoice(imported))

        val secondRepository = LocalVoiceCatalogRepository(store = store)
        val voice = secondRepository.voices.value.first { it.id == "imported-ready" }

        assertEquals(VoiceModelSource.IMPORTED, voice.source)
        assertEquals(VoiceModelStatus.Installed, voice.status)
        assertEquals("content://voice/imported-ready", voice.modelUri)
        assertEquals("imported-ready", secondRepository.selectedVoiceId.value)
    }

    private fun importedVoice(
        id: String = "imported-ready",
        displayName: String = "Imported Ready",
        localeTag: String = "en-US",
        sampleText: String = "Ready.",
        modelUri: String? = "content://voice/imported-ready",
    ): VoiceModel =
        VoiceModel(
            id = id,
            displayName = displayName,
            toneLabel = "Custom",
            localeTag = localeTag,
            description = "Ready-made local model.",
            sizeBytes = 128L,
            source = VoiceModelSource.IMPORTED,
            status = VoiceModelStatus.Installed,
            sampleText = sampleText,
            modelUri = modelUri,
        )
}
