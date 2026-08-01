/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.backup

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

private const val TAG = "DriveBackupManager"
private const val BACKUP_FILE_NAME = "zeroclaw_backup.json"
private const val MIME_TYPE = "application/json"
private const val APP_DATA_FOLDER = "appDataFolder"

class DriveBackupManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val isAvailable: Boolean
        get() = getSignedInAccount() != null

    fun getSignedInProfile(): DriveAccountProfile? = getSignedInAccount()?.toDriveAccountProfile()

    fun getSignedInEmail(): String? = getSignedInProfile()?.email

    suspend fun upload(data: BackupData): Boolean =
        runCatching {
            uploadFile(BACKUP_FILE_NAME, json.encodeToString(data))
            true
        }.onFailure { e ->
            Log.e(TAG, "Drive upload failed", e)
        }.getOrDefault(false)

    suspend fun download(): BackupData? {
        val fileId = findBackupFileId() ?: return null
        return runCatching {
            val payload =
                withContext(Dispatchers.IO) {
                    val output = ByteArrayOutputStream()
                    buildDriveService().files().get(fileId).executeMediaAndDownloadTo(output)
                    output.toString(Charsets.UTF_8.name())
                }
            json.decodeFromString<BackupData>(payload)
        }.onFailure { e ->
            Log.e(TAG, "Drive download failed", e)
        }.getOrNull()
    }

    private suspend fun uploadFile(
        filename: String,
        payload: String,
    ) {
        val drive = buildDriveService()
        val content = ByteArrayContent.fromString(MIME_TYPE, payload)
        val existingFileId = findBackupFileId(filename, drive)

        withContext(Dispatchers.IO) {
            if (existingFileId == null) {
                val metadata =
                    DriveFile().apply {
                        name = filename
                        parents = listOf(APP_DATA_FOLDER)
                    }
                drive.files().create(metadata, content).setFields("id").execute()
            } else {
                drive.files().update(existingFileId, null, content).setFields("id").execute()
            }
        }
    }

    private suspend fun findBackupFileId(
        filename: String = BACKUP_FILE_NAME,
        drive: Drive? = null,
    ): String? {
        val effectiveDrive = drive ?: buildDriveService()
        return withContext(Dispatchers.IO) {
            effectiveDrive.files()
                .list()
                .setSpaces(APP_DATA_FOLDER)
                .setQ("name = '$filename' and trashed = false")
                .setFields("files(id, name)")
                .execute()
                .files
                ?.firstOrNull()
                ?.id
        }
    }

    private suspend fun buildDriveService(): Drive =
        withContext(Dispatchers.IO) {
            val account = requireSignedInAccount()
            val selectedAccount =
                requireNotNull(account.account) {
                    "Signed-in Google account is missing an Android Account handle"
                }
            val credential =
                GoogleAccountCredential.usingOAuth2(
                    context,
                    listOf(DriveScopes.DRIVE_APPDATA),
                ).apply {
                    selectedAccountName = selectedAccount.name
                }

            Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential,
            )
                .setApplicationName(context.packageName)
                .build()
        }

    private fun getSignedInAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return if (GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
            account
        } else {
            null
        }
    }

    private fun requireSignedInAccount(): GoogleSignInAccount =
        requireNotNull(getSignedInAccount()) {
            "Google Drive backup requires a signed-in account with Drive appData scope"
        }
}

private fun GoogleSignInAccount.toDriveAccountProfile(): DriveAccountProfile? {
    val resolvedEmail = email ?: account?.name ?: return null
    return DriveAccountProfile(
        email = resolvedEmail,
        displayName = displayName?.trim()?.ifBlank { null },
    )
}
