package com.zeroclaw.android.service.devicecontrol

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

class AppLauncher(private val context: Context) {
    data class LaunchResult(
        val success: Boolean,
        val message: String,
        val packageName: String? = null,
        val errorCode: DeviceControlResult.ErrorCode? = null,
        val retryable: Boolean = false,
        val diagnostics: LaunchDiagnostics? = null,
    )

    data class LaunchDiagnostics(
        val requestedApp: String,
        val normalizedQuery: String,
        val candidateCount: Int,
        val topCandidates: List<String>,
        val resolvedPackage: String?,
        val launchIntentFound: Boolean,
        val foregroundPackageAfterLaunch: String?,
        val failureReason: String?,
    )

    private data class CachedAppEntry(
        val packageName: String,
        val label: String,
        val normalizedLabel: String,
    )

    suspend fun launch(appName: String, explicitPackage: String? = null): LaunchResult {
        val pm = context.packageManager
        val normalizedQuery = normalizeAppLabel(appName)
        val appList = getInstalledAppsCached(pm)

        val pkg = resolveLaunchablePackage(pm, appList, appName, normalizedQuery, explicitPackage)

        if (pkg == null) {
            val diagnostics = buildDiagnostics(appName, normalizedQuery, appList, null, false, null)
            Log.w(TAG, "App not found: '$appName' (normalized='$normalizedQuery')")
            return LaunchResult(
                success = false,
                message = "App \"$appName\" was not found on this device.",
                errorCode = DeviceControlResult.ErrorCode.APP_NOT_FOUND,
                retryable = false,
                diagnostics = diagnostics,
            )
        }

        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            val diagnostics = buildDiagnostics(appName, normalizedQuery, appList, pkg, false, null)
            Log.w(TAG, "No launchable activity for '$appName' (pkg=$pkg)")
            return LaunchResult(
                success = false,
                message = "App \"$appName\" is installed but has no launchable activity.",
                packageName = pkg,
                errorCode = DeviceControlResult.ErrorCode.APP_NOT_LAUNCHABLE,
                retryable = false,
                diagnostics = diagnostics,
            )
        }

        return try {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            context.startActivity(intent)

            // Non-blocking poll-based foreground verification.
            var fgPkg: String? = null
            var launchedCorrectly = false
            for (delayMs in longArrayOf(200, 400, 600, 800)) {
                delay(delayMs)
                fgPkg = getForegroundPackage()
                launchedCorrectly = fgPkg == pkg || fgPkg?.startsWith(pkg) == true
                if (launchedCorrectly) break
                Log.d(TAG, "Launch '$appName' poll fg=$fgPkg (expected=$pkg) after ${delayMs}ms")
            }

            val diagnostics = buildDiagnostics(appName, normalizedQuery, appList, pkg, true, fgPkg)
            Log.i(TAG, "Launch '$appName' pkg=$pkg intent_ok=true fg_after=$fgPkg verified=$launchedCorrectly")

            if (launchedCorrectly) {
                LaunchResult(
                    success = true,
                    message = "Opened $appName.",
                    packageName = pkg,
                    diagnostics = diagnostics,
                )
            } else {
                Log.w(TAG, "Launch '$appName' startActivity succeeded but fg=$fgPkg (expected=$pkg)")
                LaunchResult(
                    success = false,
                    message = "Launched $appName but it may not have opened correctly (foreground: $fgPkg).",
                    packageName = pkg,
                    errorCode = DeviceControlResult.ErrorCode.APP_LAUNCH_FOREGROUND_MISMATCH,
                    retryable = true,
                    diagnostics = diagnostics,
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException launching '$appName' pkg=$pkg: ${e.message}")
            val diagnostics = buildDiagnostics(appName, normalizedQuery, appList, pkg, true, null, e.message)
            LaunchResult(
                success = false,
                message = "Cannot launch $appName due to Android restrictions: ${e.message}",
                packageName = pkg,
                errorCode = DeviceControlResult.ErrorCode.APP_LAUNCH_FAILED,
                retryable = false,
                diagnostics = diagnostics,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch '$appName' pkg=$pkg: ${e.message}")
            val diagnostics = buildDiagnostics(appName, normalizedQuery, appList, pkg, true, null, e.message)
            LaunchResult(
                success = false,
                message = "Failed to open $appName: ${e.message}",
                packageName = pkg,
                errorCode = DeviceControlResult.ErrorCode.APP_LAUNCH_FAILED,
                retryable = true,
                diagnostics = diagnostics,
            )
        }
    }

    private fun getInstalledAppsCached(pm: PackageManager): List<CachedAppEntry> {
        val now = System.currentTimeMillis()
        if (now - lastCacheTime < CACHE_TTL_MS && cachedAppList.isNotEmpty()) {
            return cachedAppList
        }
        val apps = try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.w(TAG, "getInstalledApplications failed: ${e.message}")
            emptyList()
        }
        val entries = apps.map { info ->
            val label = pm.getApplicationLabel(info).toString()
            CachedAppEntry(info.packageName, label, normalizeAppLabel(label))
        }
        cachedAppList = entries
        lastCacheTime = now
        return entries
    }

    private fun findPackage(apps: List<CachedAppEntry>, query: String): String? {
        return apps.sortedByDescending { scoreMatch(it.label, query) }
            .firstOrNull { scoreMatch(it.label, query) > 0 }
            ?.packageName
    }

    private fun findPackageFuzzy(apps: List<CachedAppEntry>, normalizedQuery: String): String? {
        return apps.sortedByDescending { entry ->
            when {
                entry.normalizedLabel == normalizedQuery -> 5
                entry.normalizedLabel.startsWith(normalizedQuery) -> 4
                entry.normalizedLabel.contains(normalizedQuery) -> 3
                normalizedQuery.startsWith(entry.normalizedLabel) -> 2
                normalizedQuery.split(" ").all { w -> entry.normalizedLabel.contains(w) } -> 1
                entry.packageName.contains(normalizedQuery) -> 1
                else -> 0
            }
        }.firstOrNull { entry ->
            entry.normalizedLabel == normalizedQuery ||
            entry.normalizedLabel.startsWith(normalizedQuery) ||
            entry.normalizedLabel.contains(normalizedQuery) ||
            normalizedQuery.startsWith(entry.normalizedLabel) ||
            normalizedQuery.split(" ").all { w -> entry.normalizedLabel.contains(w) } ||
            entry.packageName.contains(normalizedQuery)
        }?.packageName
    }

    private fun resolveLaunchablePackage(
        pm: PackageManager,
        apps: List<CachedAppEntry>,
        appName: String,
        normalizedQuery: String,
        explicitPackage: String?,
    ): String? {
        if (explicitPackage != null) {
            if (pm.getLaunchIntentForPackage(explicitPackage) != null) {
                return explicitPackage
            }
            Log.w(TAG, "Explicit package '$explicitPackage' for '$appName' is not launchable; trying installed alternatives")
        }
        return findPackage(apps, appName)
            ?: findPackageFuzzy(apps, normalizedQuery)
            ?: resolveByKnownPackages(pm, normalizedQuery)
    }

    private fun normalizeAppLabel(label: String): String {
        return label.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun scoreMatch(label: String, query: String): Int = when {
        label.equals(query, true) -> 3
        label.startsWith(query, true) -> 2
        label.contains(query, true) -> 1
        else -> 0
    }

    private fun getForegroundPackage(): String? {
        return try {
            val service = DeviceControlAccessibilityService.instance()
            service?.currentPackage()
        } catch (e: Exception) {
            Log.w(TAG, "getForegroundPackage failed: ${e.message}")
            null
        }
    }

    private fun buildDiagnostics(
        requestedApp: String,
        normalizedQuery: String,
        apps: List<CachedAppEntry>,
        resolvedPkg: String?,
        intentFound: Boolean,
        fgAfter: String?,
        failureReason: String? = null,
    ): LaunchDiagnostics {
        val candidates = apps
            .filter { scoreMatch(it.label, requestedApp) > 0 || it.normalizedLabel.contains(normalizedQuery) }
            .sortedByDescending { scoreMatch(it.label, requestedApp) }
            .map { it.label }
            .take(5)

        return LaunchDiagnostics(
            requestedApp = requestedApp,
            normalizedQuery = normalizedQuery,
            candidateCount = candidates.size,
            topCandidates = candidates,
            resolvedPackage = resolvedPkg,
            launchIntentFound = intentFound,
            foregroundPackageAfterLaunch = fgAfter,
            failureReason = failureReason,
        )
    }

    /**
     * Resolves an app by checking a curated list of well-known package name candidates.
     * Used as a last resort when label-based lookup fails (e.g. user says "Chrome" but the
     * installed browser is Brave or Samsung Internet).
     */
    private fun resolveByKnownPackages(pm: PackageManager, normalizedQuery: String): String? {
        val candidates = WELL_KNOWN_PACKAGES
            .entries
            .filter { (key, _) -> key == normalizedQuery || normalizedQuery.contains(key) || key.contains(normalizedQuery) }
            .flatMap { it.value }

        for (pkg in candidates) {
            if (isPackageInstalled(pm, pkg)) {
                Log.d(TAG, "resolveByKnownPackages: '$normalizedQuery' → $pkg")
                return pkg
            }
        }
        return null
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getLaunchIntentForPackage(pkg) != null
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "AppLauncher"
        private const val CACHE_TTL_MS = 60_000L
        @Volatile private var cachedAppList: List<CachedAppEntry> = emptyList()
        @Volatile private var lastCacheTime: Long = 0L

        /**
         * Well-known package name candidates keyed by normalized app label.
         * Ordered from most common to least common install on Android.
         */
        val WELL_KNOWN_PACKAGES: Map<String, List<String>> = mapOf(
            // Browsers
            "chrome" to listOf(
                "com.android.chrome",
                "com.brave.browser",
                "com.microsoft.emmx",
                "org.mozilla.firefox",
                "com.opera.browser",
                "com.sec.android.app.sbrowser",
                "com.UCMobile.intl",
            ),
            "browser" to listOf(
                "com.android.chrome",
                "com.brave.browser",
                "com.sec.android.app.sbrowser",
                "com.microsoft.emmx",
                "org.mozilla.firefox",
                "com.opera.browser",
            ),
            "brave" to listOf("com.brave.browser"),
            "firefox" to listOf("org.mozilla.firefox", "org.mozilla.firefox_beta"),
            "edge" to listOf("com.microsoft.emmx"),
            "opera" to listOf("com.opera.browser", "com.opera.mini.native"),
            "samsung internet" to listOf("com.sec.android.app.sbrowser"),
            "uc browser" to listOf("com.UCMobile.intl"),
            // Messaging
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            "signal" to listOf("org.thoughtcrime.securesms"),
            "messages" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            "messenger" to listOf("com.facebook.orca"),
            "instagram" to listOf("com.instagram.android"),
            "snapchat" to listOf("com.snapchat.android"),
            "twitter" to listOf("com.twitter.android"),
            "x" to listOf("com.twitter.android"),
            "discord" to listOf("com.discord"),
            "slack" to listOf("com.Slack"),
            "linkedin" to listOf("com.linkedin.android"),
            "facebook" to listOf("com.facebook.katana"),
            "skype" to listOf("com.skype.raider"),
            "teams" to listOf("com.microsoft.teams"),
            "zoom" to listOf("us.zoom.videomeetings"),
            // Google Apps
            "gmail" to listOf("com.google.android.gm"),
            "google maps" to listOf("com.google.android.apps.maps"),
            "maps" to listOf("com.google.android.apps.maps"),
            "youtube" to listOf("com.google.android.youtube"),
            "youtube music" to listOf("com.google.android.apps.youtube.music"),
            "google photos" to listOf("com.google.android.apps.photos"),
            "photos" to listOf(
                "com.google.android.apps.photos",
                "com.samsung.android.gallery3d",
            ),
            "google drive" to listOf("com.google.android.apps.docs"),
            "drive" to listOf("com.google.android.apps.docs"),
            "google docs" to listOf("com.google.android.apps.docs.editors.docs"),
            "google sheets" to listOf("com.google.android.apps.docs.editors.sheets"),
            "google meet" to listOf("com.google.android.apps.meetings"),
            "meet" to listOf("com.google.android.apps.meetings"),
            "google calendar" to listOf("com.google.android.calendar"),
            "calendar" to listOf(
                "com.google.android.calendar",
                "com.samsung.android.calendar",
            ),
            "google play" to listOf("com.android.vending"),
            "google play store" to listOf("com.android.vending"),
            "google play store app" to listOf("com.android.vending"),
            "play store" to listOf("com.android.vending"),
            "play store app" to listOf("com.android.vending"),
            "google pay" to listOf("com.google.android.apps.nbu.paisa.user"),
            // Media & Music
            "spotify" to listOf("com.spotify.music"),
            "netflix" to listOf("com.netflix.mediaclient"),
            "amazon prime" to listOf("com.amazon.avod.thirdpartyclient"),
            "prime video" to listOf("com.amazon.avod.thirdpartyclient"),
            "hotstar" to listOf("in.startv.hotstar"),
            "disney hotstar" to listOf("in.startv.hotstar"),
            "vlc" to listOf("org.videolan.vlc"),
            "mx player" to listOf("com.mxtech.videoplayer.ad"),
            "amazon music" to listOf("com.amazon.mp3"),
            // Samsung Apps
            "samsung health" to listOf("com.sec.android.app.shealth"),
            "bixby" to listOf("com.samsung.android.bixby.agent"),
            "samsung pay" to listOf("com.samsung.android.spay"),
            "one ui home" to listOf("com.sec.android.app.launcher"),
            // Productivity
            "files" to listOf(
                "com.google.android.apps.nbu.files",
                "com.sec.android.app.myfiles",
                "com.android.documentsui",
            ),
            "settings" to listOf("com.android.settings"),
            "camera" to listOf(
                "com.android.camera2",
                "com.sec.android.app.camera",
                "com.google.android.GoogleCamera",
            ),
            "clock" to listOf(
                "com.android.deskclock",
                "com.sec.android.app.clockpackage",
            ),
            "calculator" to listOf(
                "com.android.calculator2",
                "com.sec.android.app.popupcalculator",
            ),
            "contacts" to listOf(
                "com.android.contacts",
                "com.samsung.android.contacts",
                "com.google.android.contacts",
            ),
            "phone" to listOf(
                "com.android.phone",
                "com.samsung.android.dialer",
                "com.google.android.dialer",
            ),
            "dialer" to listOf(
                "com.android.phone",
                "com.samsung.android.dialer",
                "com.google.android.dialer",
            ),
            // E-commerce & Finance
            "amazon" to listOf(
                "com.amazon.mShop.android.shopping",
                "in.amazon.mShop.android.shopping",
            ),
            "flipkart" to listOf("com.flipkart.android"),
            "paytm" to listOf("net.one97.paytm"),
            "phonepe" to listOf("com.phonepe.app"),
            "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
        )
    }
}
