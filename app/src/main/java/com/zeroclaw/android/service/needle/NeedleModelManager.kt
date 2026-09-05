package com.zeroclaw.android.service.needle

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stages the bundled Needle 2 model (`assets/needle/needle2.cact`, Apache-2.0)
 * into app-private storage.
 *
 * Bundled-only in v1: no network path. Copies asset → `.tmp` → atomic rename
 * on first use, mirroring the LiteRT download convention.
 */
class NeedleModelManager(private val appContext: Context) {

    fun modelFile(): File = File(appContext.filesDir, "$MODEL_DIR/$MODEL_FILE")

    suspend fun ensureModel(): File = withContext(Dispatchers.IO) {
        val target = modelFile()
        if (target.isFile && target.length() >= MIN_MODEL_BYTES) return@withContext target
        target.parentFile?.mkdirs()
        val tmp = File(target.parent, "$MODEL_FILE.tmp")
        try {
            appContext.assets.open(ASSET_PATH).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() < MIN_MODEL_BYTES) {
                tmp.delete()
                throw IllegalStateException("Bundled Needle model failed size guard")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "ensureModel failed: ${e.message}")
            throw e
        }
        target
    }

    companion object {
        private const val TAG = "NeedleModel"
        const val ASSET_PATH = "needle/needle2.cact"
        const val MODEL_FILE = "needle2.cact"
        const val MODEL_DIR = "needle_models"

        /** Minimum credible size for the 13.7MB model file. */
        const val MIN_MODEL_BYTES = 10_000_000L
    }
}
