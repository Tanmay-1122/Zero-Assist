package com.zeroclaw.android.backup

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.zeroclaw.android.data.SecurePrefsProvider
import com.zeroclaw.android.data.local.dao.AgentDao
import com.zeroclaw.android.data.local.dao.ConnectedChannelDao
import com.zeroclaw.android.data.local.dao.PluginDao
import com.zeroclaw.android.data.local.entity.AgentEntity
import com.zeroclaw.android.data.local.entity.ConnectedChannelEntity
import com.zeroclaw.android.data.local.entity.PluginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "SyncRepository"
private const val SYNC_PREFS_NAME = "zeroclaw_sync_prefs"
private const val KEY_PENDING_SYNC = "pending_sync"
private const val KEY_SIGNED_IN_EMAIL = "signed_in_email"
private const val SECURE_SETTINGS_PREFS = "secure_settings"

private val BACKUP_SECRET_KEYS = listOf(
    "sec_tunnel_cf_token",
    "sec_tunnel_ngrok_token",
    "sec_gw_paired_tokens",
    "sec_composio_api_key",
    "sec_web_search_brave_api_key",
    "sec_memory_qdrant_api_key",
    "sec_reliability_api_keys_json",
)

/**
 * Enumeration of synchronization states for backup operations.
 *
 * @property IDLE Default idle state, no sync in progress.
 * @property SYNCING Sync operation is currently running.
 * @property SUCCESS Last sync completed successfully.
 * @property FAILED Last sync failed with an error.
 */
enum class SyncStatus { IDLE, SYNCING, SUCCESS, FAILED }

/**
 * Manages synchronization of application data to Google Drive.
 *
 * Coordinates backup/restore operations for agents, plugins, channels,
 * settings, and secrets. Tracks sync state and pending sync markers.
 * Automatically backs up when data changes via [uploadToDrive].
 *
 * The [syncStatus] flow can be observed for real-time sync state updates.
 *
 * @param context Android application context for preferences access.
 * @param settingsDataStore Singleton DataStore instance for shared app settings.
 * @param driveBackupManager Manager for Drive upload/download operations.
 * @param agentDao DAO for agent persistence.
 * @param pluginDao DAO for plugin persistence.
 * @param connectedChannelDao DAO for connected channel persistence.
 */
class SyncRepository(
    private val context: Context,
    private val settingsDataStore: DataStore<Preferences>,
    private val driveBackupManager: DriveBackupManager,
    private val agentDaoProvider: () -> AgentDao,
    private val pluginDaoProvider: () -> PluginDao,
    private val connectedChannelDaoProvider: () -> ConnectedChannelDao,
) {
    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)

    @Volatile
    private var isRestoring = false

    /**
     * Observable flow of the current synchronization status.
     *
     * Emits [SyncStatus.SYNCING] when a sync is in progress,
     * [SyncStatus.SUCCESS] on completion, [SyncStatus.FAILED] on error.
     */
    val syncStatus: StateFlow<SyncStatus> = _syncStatus

    private val agentDao: AgentDao
        get() = agentDaoProvider()

    private val pluginDao: PluginDao
        get() = pluginDaoProvider()

    private val connectedChannelDao: ConnectedChannelDao
        get() = connectedChannelDaoProvider()

    private val syncPrefs by lazy {
        context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Mark that a sync operation needs to happen.
     *
     * Called after data mutations (agent add/edit, plugin install, etc.)
     * to trigger periodic sync if enabled.
     */
    fun markPendingSync() {
        syncPrefs.edit().putBoolean(KEY_PENDING_SYNC, true).apply()
    }

    /**
     * Clear the pending sync marker after sync completes.
     */
    fun clearPendingSync() {
        syncPrefs.edit().putBoolean(KEY_PENDING_SYNC, false).apply()
    }

    /**
     * Check whether a sync operation is pending.
     *
     * @return true if sync should be triggered next.
     */
    fun hasPendingSync(): Boolean = syncPrefs.getBoolean(KEY_PENDING_SYNC, false)

    /**
     * Save the email address of the currently signed-in Google account.
     *
     * @param email User's Google account email.
     */
    fun saveSignedInEmail(email: String) {
        syncPrefs.edit().putString(KEY_SIGNED_IN_EMAIL, email).apply()
    }

    /**
     * Retrieve the signed-in Google account details.
     *
     * Falls back to the last stored email so the UI can still render a basic
     * account label while the Google client state is being refreshed.
     */
    fun getSignedInProfile(): DriveAccountProfile? =
        driveBackupManager.getSignedInProfile()
            ?: syncPrefs.getString(KEY_SIGNED_IN_EMAIL, null)?.let { email ->
                DriveAccountProfile(email = email)
            }

    /**
     * Retrieve the email address of the currently signed-in Google account.
     *
     * @return Email address if user is signed in, null otherwise.
     */
    fun getSignedInEmail(): String? = getSignedInProfile()?.email

    fun hasSignedInAccount(): Boolean = driveBackupManager.isAvailable

    fun reconcileSignedInProfile(): DriveAccountProfile? {
        val profile = driveBackupManager.getSignedInProfile()
        if (profile == null) {
            clearSignedInEmail()
            return null
        }
        saveSignedInEmail(profile.email)
        return profile
    }

    fun reconcileSignedInAccount(): String? {
        return reconcileSignedInProfile()?.email
    }

    /**
     * Clear the signed-in user email (logout).
     */
    fun clearSignedInEmail() {
        syncPrefs.edit().remove(KEY_SIGNED_IN_EMAIL).apply()
    }

    /**
     * Uploads current application state to Google Drive.
     *
     * Collects agents, plugins, channels, settings, and secrets,
     * then uploads them as an atomic backup. Updates [syncStatus] with progression.
     *
     * Must be called from a coroutine scope.
     */
    suspend fun uploadToDrive() {
        if (isRestoring) {
            Log.d(TAG, "Upload skipped: restore is in progress")
            return
        }
        _syncStatus.value = SyncStatus.SYNCING

        val email = reconcileSignedInAccount()
        if (email == null) {
            Log.e(TAG, "Upload failed: no signed-in account email")
            _syncStatus.value = SyncStatus.FAILED
            return
        }

        try {
            val agents = agentDao.observeAll().first().map { entity ->
                AgentBackup(
                    id = entity.id,
                    name = entity.name,
                    provider = entity.provider,
                    modelName = entity.modelName,
                    isEnabled = entity.isEnabled,
                    systemPrompt = entity.systemPrompt,
                    channelsJson = entity.channelsJson,
                    temperature = entity.temperature,
                    maxDepth = entity.maxDepth,
                    thinkingLevel = entity.thinkingLevel,
                    role = entity.role,
                    avatar = entity.avatar,
                    tagsJson = entity.tagsJson,
                    isMaster = entity.isMaster,
                    priority = entity.priority,
                    templateId = entity.templateId,
                    accentColor = entity.accentColor,
                )
            }

            val plugins = pluginDao.observeAll().first().map { entity ->
                PluginBackup(
                    id = entity.id,
                    name = entity.name,
                    description = entity.description,
                    version = entity.version,
                    author = entity.author,
                    category = entity.category,
                    isInstalled = entity.isInstalled,
                    isEnabled = entity.isEnabled,
                    configJson = entity.configJson,
                    remoteVersion = entity.remoteVersion,
                )
            }

            val channels = connectedChannelDao.observeAll().first().map { entity ->
                ConnectedChannelBackup(
                    id = entity.id,
                    type = entity.type,
                    isEnabled = entity.isEnabled,
                    configJson = entity.configJson,
                    createdAt = entity.createdAt,
                )
            }

            val rawPrefs = settingsDataStore.data.first()
            val settings: Map<String, String> = rawPrefs.asMap()
                .mapKeys { it.key.name }
                .mapValues { it.value.toString() }

            val securePrefs = SecurePrefsProvider.create(context, SECURE_SETTINGS_PREFS).first
            val secrets: Map<String, String> = BACKUP_SECRET_KEYS.mapNotNull { key ->
                val value = securePrefs.getString(key, null)
                if (value != null) key to value else null
            }.toMap()

            val backupData = BackupData(
                agents = agents,
                plugins = plugins,
                connectedChannels = channels,
                settings = settings,
                secrets = secrets,
            )

            val success = driveBackupManager.upload(backupData)
            if (success) {
                clearPendingSync()
                _syncStatus.value = SyncStatus.SUCCESS
            } else {
                _syncStatus.value = SyncStatus.FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            _syncStatus.value = SyncStatus.FAILED
        }
    }

    /**
     * Restores application state from a Google Drive backup.
     *
     * Downloads the latest backup and restores agents, plugins, channels,
     * settings, and secrets to their backed-up state. Updates [syncStatus]
     * throughout the operation.
     *
     * Must be called from a coroutine scope.
     */
    suspend fun restoreFromDrive() {
        isRestoring = true
        _syncStatus.value = SyncStatus.SYNCING

        val email = reconcileSignedInAccount()
        if (email == null) {
            Log.e(TAG, "Restore failed: no signed-in account email")
            _syncStatus.value = SyncStatus.FAILED
            isRestoring = false
            return
        }

        val backupData = driveBackupManager.download()
        if (backupData == null) {
            Log.i(TAG, "No Drive backup found; treating restore as a clean first sync")
            markPendingSync()
            _syncStatus.value = SyncStatus.SUCCESS
            isRestoring = false
            return
        }

        try {
            restoreBackupDataToDatabases(backupData)
            restoreSettingsFromBackup(backupData)
            restoreSecretsFromBackup(backupData)
            _syncStatus.value = SyncStatus.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            _syncStatus.value = SyncStatus.FAILED
        } finally {
            isRestoring = false
        }
    }

    /**
     * Restores agents, plugins, and channels from backup data to databases.
     *
     * Each table is restored independently so a failure in one table
     * (e.g. channel unique constraint) does not abort the others.
     */
    private suspend fun restoreBackupDataToDatabases(backupData: BackupData) {
        withContext(Dispatchers.IO) {
            runCatching { restoreAgentsFromBackup(backupData) }
                .onFailure { Log.e(TAG, "Agent restore failed", it) }
            runCatching { restorePluginsFromBackup(backupData) }
                .onFailure { Log.e(TAG, "Plugin restore failed", it) }
            runCatching { restoreChannelsFromBackup(backupData) }
                .onFailure { Log.e(TAG, "Channel restore failed", it) }
        }
    }

    /**
     * Restores agent configurations from backup.
     */
    private suspend fun restoreAgentsFromBackup(backupData: BackupData) {
        backupData.agents.forEach { backup ->
            agentDao.upsert(
                AgentEntity(
                    id = backup.id,
                    name = backup.name,
                    provider = backup.provider,
                    modelName = backup.modelName,
                    isEnabled = backup.isEnabled,
                    systemPrompt = backup.systemPrompt,
                    channelsJson = backup.channelsJson,
                    temperature = backup.temperature,
                    maxDepth = backup.maxDepth,
                    thinkingLevel = backup.thinkingLevel,
                    role = backup.role,
                    avatar = backup.avatar,
                    tagsJson = backup.tagsJson,
                    isMaster = backup.isMaster,
                    priority = backup.priority,
                    templateId = backup.templateId,
                    accentColor = backup.accentColor,
                )
            )
        }
    }

    /**
     * Restores plugin configurations from backup.
     */
    private suspend fun restorePluginsFromBackup(backupData: BackupData) {
        backupData.plugins.forEach { backup ->
            pluginDao.upsert(
                PluginEntity(
                    id = backup.id,
                    name = backup.name,
                    description = backup.description,
                    version = backup.version,
                    author = backup.author,
                    category = backup.category,
                    isInstalled = backup.isInstalled,
                    isEnabled = backup.isEnabled,
                    configJson = backup.configJson,
                    remoteVersion = backup.remoteVersion,
                )
            )
        }
    }

    /**
     * Restores channel configurations from backup.
     *
     * Deletes all existing channels first to avoid unique constraint
     * conflicts on the `type` column when backup channels have different
     * IDs but the same type as local rows.
     */
    private suspend fun restoreChannelsFromBackup(backupData: BackupData) {
        connectedChannelDao.observeAll().first().forEach { existing ->
            connectedChannelDao.deleteById(existing.id)
        }
        backupData.connectedChannels.forEach { backup ->
            connectedChannelDao.upsert(
                ConnectedChannelEntity(
                    id = backup.id,
                    type = backup.type,
                    isEnabled = backup.isEnabled,
                    configJson = backup.configJson,
                    createdAt = backup.createdAt,
                )
            )
        }
    }

    /**
     * Restores user settings from backup with type conversion.
     */
    private suspend fun restoreSettingsFromBackup(backupData: BackupData) {
        backupData.settings.forEach { (keyName, value) ->
            restoreSingleSetting(context, keyName, value)
        }
    }

    /**
     * Restores a single setting, inferring type from string value.
     */
    private suspend fun restoreSingleSetting(
        context: Context,
        keyName: String,
        value: String,
    ) {
        val boolVal = value.toBooleanStrictOrNull()
        val intVal = if (boolVal == null) value.toIntOrNull() else null
        val floatVal = if (boolVal == null && intVal == null) value.toFloatOrNull() else null
        val longVal = if (boolVal == null && intVal == null && floatVal == null) value.toLongOrNull() else null

        settingsDataStore.edit { prefs ->
            when {
                boolVal != null -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(keyName)] = boolVal
                intVal != null -> prefs[androidx.datastore.preferences.core.intPreferencesKey(keyName)] = intVal
                floatVal != null -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(keyName)] = floatVal
                longVal != null -> prefs[androidx.datastore.preferences.core.longPreferencesKey(keyName)] = longVal
                else -> prefs[androidx.datastore.preferences.core.stringPreferencesKey(keyName)] = value
            }
        }
    }

    /**
     * Restores encrypted secrets from backup.
     */
    private suspend fun restoreSecretsFromBackup(backupData: BackupData) {
        withContext(Dispatchers.IO) {
            val securePrefs = SecurePrefsProvider.create(context, SECURE_SETTINGS_PREFS).first
            backupData.secrets.forEach { (key, value) ->
                securePrefs.edit().putString(key, value).apply()
            }
        }
    }
}
