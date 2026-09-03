/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.google

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.DriveScopes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private const val TAG = "GoogleWorkspaceAuthManager"
private const val CREDENTIALS_FILENAME = "gws_credentials.json"

class GoogleWorkspaceAuthManager(private val context: Context) {

    val GWS_SCOPES = listOf(
        DriveScopes.DRIVE,
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/calendar",
        "https://www.googleapis.com/auth/spreadsheets",
        "https://www.googleapis.com/auth/documents",
        "https://www.googleapis.com/auth/presentations",
        "https://www.googleapis.com/auth/tasks",
        "https://www.googleapis.com/auth/contacts.readonly",
        "https://www.googleapis.com/auth/chat.messages",
        "https://www.googleapis.com/auth/forms.body",
    )

    private val signInOptions: GoogleSignInOptions
        get() = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .also { builder -> GWS_SCOPES.forEach { builder.requestScopes(Scope(it)) } }
            .build()

    val isSignedIn: Boolean
        get() {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
            if (!GWS_SCOPES.all { GoogleSignIn.hasPermissions(account, Scope(it)) }) return false
            // Also verify credentials were actually exported to the sandbox
            val sandboxHome = context.getExternalFilesDir(null) ?: return false
            return File(sandboxHome, "sandbox-home/.config/gws/$CREDENTIALS_FILENAME").exists()
        }

    fun getAccountEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    fun getSignInIntent(): Intent =
        GoogleSignIn.getClient(context, signInOptions).signInIntent

    suspend fun handleSignInResult(data: Intent?): Boolean =
        runCatching {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(com.google.android.gms.common.api.ApiException::class.java)

            Log.d(TAG, "Sign-in successful for: ${account.email}")
            GoogleWorkspaceAuditLogger.logSignIn(account.email ?: "unknown", true)
            exportCredentials(account)
        }.onFailure { e ->
            Log.e(TAG, "Sign-in failed: ${e.message}", e)
            GoogleWorkspaceAuditLogger.logSignIn("unknown", false)
        }.isSuccess

    fun signOut(onComplete: () -> Unit = {}) {
        GoogleSignIn.getClient(context, signInOptions)
            .signOut()
            .addOnCompleteListener {
                clearCredentials()
                onComplete()
            }
    }

    fun getCredentialsSandboxPath(): String =
        "/root/.config/gws/$CREDENTIALS_FILENAME"

    private suspend fun exportCredentials(account: GoogleSignInAccount): Boolean =
        withContext(Dispatchers.IO) {
            val credentialsJson = JSONObject().apply {
                put("type", "authorized_user")
                put("client_id", "android-app")
                put("client_secret", "")
                put("refresh_token", getRefreshToken(account))
                put("token_uri", "https://oauth2.googleapis.com/token")
                put("scopes", org.json.JSONArray(GWS_SCOPES))
            }

            val sandboxHome = context.getExternalFilesDir(null) ?: run {
                Log.e(TAG, "External files dir not available")
                GoogleWorkspaceAuditLogger.logCredentialExport(account.email ?: "unknown", false)
                return@withContext false
            }

            val gwsConfigDir = File(sandboxHome, "sandbox-home/.config/gws")
            gwsConfigDir.mkdirs()
            val credentialsFile = File(gwsConfigDir, CREDENTIALS_FILENAME)
            credentialsFile.writeText(credentialsJson.toString(2))
            Log.d(TAG, "Credentials exported to: ${credentialsFile.absolutePath}")
            GoogleWorkspaceAuditLogger.logCredentialExport(account.email ?: "unknown", true)
            true
        }

    private fun getRefreshToken(account: GoogleSignInAccount): String {
        val accountManager = android.accounts.AccountManager.get(context)
        val googleAccount = requireNotNull(account.account) {
            "Signed-in Google account is missing an Android Account handle"
        }
        val matchingAccount = accountManager.getAccountsByType("com.google")
            .firstOrNull { it.name == googleAccount.name }
            ?: throw IllegalStateException("Account not found in AccountManager")

        return accountManager.blockingGetAuthToken(matchingAccount, "oauth2:true", true)
            ?: throw IllegalStateException("Could not retrieve OAuth2 token")
    }

    private fun clearCredentials() {
        val sandboxHome = context.getExternalFilesDir(null) ?: return
        val credentialsFile = File(sandboxHome, "sandbox-home/.config/gws/$CREDENTIALS_FILENAME")
        if (credentialsFile.exists()) {
            credentialsFile.delete()
            Log.d(TAG, "Credentials cleared")
        }
    }
}
