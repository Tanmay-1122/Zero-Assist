package com.zeroclaw.android.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zeroclaw.android.ZeroClawApplication

private const val TAG = "DriveBackupSyncWorker"

class DriveBackupSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ZeroClawApplication
        val syncRepository = app.syncRepository

        if (!app.driveBackupManager.isAvailable) {
            Log.d(TAG, "Drive backup skipped because no signed-in Drive account is available")
            return Result.success()
        }

        if (!syncRepository.hasPendingSync()) {
            Log.d(TAG, "No pending sync - skipping upload")
            return Result.success()
        }

        if (!syncRepository.hasSignedInAccount()) {
            Log.d(TAG, "Skipping Drive sync because account state is not available")
            return Result.success()
        }

        syncRepository.uploadToDrive()

        return if (syncRepository.syncStatus.value == SyncStatus.SUCCESS) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
