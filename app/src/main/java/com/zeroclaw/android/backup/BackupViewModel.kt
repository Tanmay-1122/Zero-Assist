package com.zeroclaw.android.backup

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.zeroclaw.android.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BackupViewModel(
    private val syncRepository: SyncRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val syncStatus: StateFlow<SyncStatus> = syncRepository.syncStatus
    val isSignedIn: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val userEmail: MutableStateFlow<String?> = MutableStateFlow(null)
    val displayName: MutableStateFlow<String?> = MutableStateFlow(null)
    val preferredName: MutableStateFlow<String?> = MutableStateFlow(null)

    init {
        refreshSignInState()
    }

    fun getSignInIntent(context: Context): Intent {
        return GoogleSignIn.getClient(context, buildSignInOptions()).signInIntent
    }

    fun handleSignInResult(
        context: Context,
        data: Intent?,
        onUserNameSeeded: ((String) -> Unit)? = null,
    ) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val profile = account.toDriveAccountProfile()
            if (profile == null) {
                refreshSignInState()
                return
            }
            syncRepository.saveSignedInEmail(profile.email)
            viewModelScope.launch {
                val seededName = seedUserNameIfMissing(profile.preferredName)
                if (seededName != null) {
                    onUserNameSeeded?.invoke(seededName)
                }
            }
            WorkManagerScheduler.schedulePeriodic(context.applicationContext)
            refreshSignInState()
            restoreFromDrive()
        } catch (e: ApiException) {
            refreshSignInState()
            Log.e(TAG, "Sign-in failed: ${e.statusCode}")
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            syncRepository.markPendingSync()
            syncRepository.uploadToDrive()
        }
    }

    fun onDataChanged() {
        viewModelScope.launch {
            syncRepository.markPendingSync()
            syncRepository.uploadToDrive()
        }
    }

    fun restoreFromDrive() {
        viewModelScope.launch { syncRepository.restoreFromDrive() }
    }

    fun signOut(context: Context) {
        GoogleSignIn.getClient(context, buildSignInOptions())
            .signOut()
            .addOnCompleteListener {
                syncRepository.clearSignedInEmail()
                WorkManagerScheduler.cancelPeriodic(context.applicationContext)
                refreshSignInState()
            }
    }

    fun refreshSignInState() {
        updateSignedInState(syncRepository.reconcileSignedInProfile())
    }

    private fun buildSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()

    private suspend fun seedUserNameIfMissing(
        googlePreferredName: String,
    ): String? {
        val currentSettings = settingsRepository.settings.first()
        if (identityHasUserName(currentSettings.identityJson)) {
            return null
        }

        settingsRepository.setIdentityJson(
            seedUserNameIntoIdentityJson(
                identityJson = currentSettings.identityJson,
                userName = googlePreferredName,
            ),
        )
        return googlePreferredName
    }

    private fun clearSignedInState() {
        isSignedIn.value = false
        userEmail.value = null
        displayName.value = null
        preferredName.value = null
    }

    private fun updateSignedInState(
        profile: DriveAccountProfile?,
    ) {
        if (profile == null) {
            clearSignedInState()
            return
        }

        isSignedIn.value = true
        userEmail.value = profile.email
        displayName.value = profile.displayName
        preferredName.value = profile.preferredName
    }

    companion object {
        private const val TAG = "BackupViewModel"
    }
}

private fun GoogleSignInAccount.toDriveAccountProfile(): DriveAccountProfile? {
    val resolvedEmail = email ?: account?.name ?: return null
    return DriveAccountProfile(
        email = resolvedEmail,
        displayName = displayName?.trim()?.ifBlank { null },
    )
}
