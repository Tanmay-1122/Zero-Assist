/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface VoiceContactLookupResult {
    data class Found(
        val displayName: String,
        val phoneDialUri: String,
    ) : VoiceContactLookupResult

    data object PermissionRequired : VoiceContactLookupResult

    data class NotFound(
        val contactName: String,
    ) : VoiceContactLookupResult

    data class Failed(
        val message: String,
    ) : VoiceContactLookupResult
}

interface VoiceContactLookup {
    suspend fun findPhoneDialUri(contactName: String): VoiceContactLookupResult
}

object MissingVoiceContactLookup : VoiceContactLookup {
    override suspend fun findPhoneDialUri(contactName: String): VoiceContactLookupResult =
        VoiceContactLookupResult.NotFound(contactName.trim())
}

class AndroidVoiceContactLookup(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VoiceContactLookup {
    private val appContext = context.applicationContext

    override suspend fun findPhoneDialUri(contactName: String): VoiceContactLookupResult =
        withContext(dispatcher) {
            val requestedName = contactName.trim()
            if (requestedName.isBlank()) {
                return@withContext VoiceContactLookupResult.NotFound(requestedName)
            }
            if (appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext VoiceContactLookupResult.PermissionRequired
            }

            runCatching {
                findBestContact(requestedName)
                    ?: VoiceContactLookupResult.NotFound(requestedName)
            }.getOrElse { error ->
                if (error is SecurityException) {
                    VoiceContactLookupResult.PermissionRequired
                } else {
                    VoiceContactLookupResult.Failed(
                        "Could not read local contacts: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }

    private fun findBestContact(contactName: String): VoiceContactLookupResult.Found? {
        val target = contactName.normalizedContactName()
        if (target.isBlank()) return null

        var bestMatch: ContactMatch? = null
        appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameIndex =
                cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex =
                cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIndex < 0 || numberIndex < 0) {
                return null
            }

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex).orEmpty().trim()
                val number = cursor.getString(numberIndex).orEmpty().trim()
                val dialUri = VoiceAssistantActionParser.phoneDialUriForNumber(number) ?: continue
                val score = displayName.matchScore(target)
                if (score <= 0) continue

                val candidate =
                    ContactMatch(
                        displayName = displayName.ifBlank { contactName },
                        phoneDialUri = dialUri,
                        score = score,
                    )
                if (bestMatch == null || candidate.score > requireNotNull(bestMatch).score) {
                    bestMatch = candidate
                }
            }
        }

        return bestMatch?.let { match ->
            VoiceContactLookupResult.Found(
                displayName = match.displayName,
                phoneDialUri = match.phoneDialUri,
            )
        }
    }

    private fun String.matchScore(target: String): Int {
        val name = normalizedContactName()
        return when {
            name == target -> 4
            name.split(' ').any { part -> part == target } -> 3
            name.startsWith(target) -> 2
            name.contains(target) -> 1
            else -> 0
        }
    }

    private fun String.normalizedContactName(): String =
        lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private data class ContactMatch(
        val displayName: String,
        val phoneDialUri: String,
        val score: Int,
    )
}
