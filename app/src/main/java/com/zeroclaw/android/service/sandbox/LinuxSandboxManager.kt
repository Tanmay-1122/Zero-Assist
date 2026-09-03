/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "LinuxSandboxManager"
private const val MIN_DISK_SPACE_MB = 300L
private val TRANSCRIPT_SAVE_DEBOUNCE = 500.milliseconds

/** Exception that carries a recoverable flag for UI retry logic. */
private class SandboxSetupException(message: String, val recoverable: Boolean = true) :
    IllegalStateException(message)

/**
 * Manages the full lifecycle of the self-contained Alpine Linux proot sandbox.
 *
 * Responsibilities:
 *  - Detecting whether the sandbox is already installed on startup.
 *  - Downloading and extracting the Alpine rootfs on first use.
 *  - Creating [ProotExecutor] and [SessionShell] instances for callers.
 *  - Tracking and exposing live [SandboxState] via [state].
 *
 * One [SessionShell] is created per logical session ID. Shells live for the
 * duration of the app process; they are torn down on [reset] or when a
 * conversation is explicitly closed via [closeShell].
 */
class LinuxSandboxManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private val _state = MutableStateFlow<SandboxState>(SandboxState.NotInstalled)
    val state: StateFlow<SandboxState> = _state

    /** Manages background proot processes started with background=true. */
    val processManager: SandboxProcessManager by lazy { SandboxProcessManager(this) }

    private val sandboxDir: File
        get() = File(context.filesDir, "linux-sandbox")

    val rootfsPath: String get() = File(sandboxDir, "rootfs").absolutePath

    // Proot binaries live in the system-managed nativeLibraryDir, which is
    // always mounted exec (unlike filesDir which may be noexec on Android 10+).
    // This resolves the "proot error: execve("/bin/sh"): Permission denied" error.
    private val systemNativeLibDir: String
        get() = context.applicationInfo.nativeLibraryDir

    // Sandbox /root is bind-mounted from externally-visible app storage so files
    // produced by the agent can be opened via FileProvider Intents.
    val homePath: String by lazy {
        val external = context.getExternalFilesDir(null)
        val target = if (external != null) {
            File(external, "sandbox-home")
        } else {
            File(sandboxDir, "home")
        }
        target.mkdirs()
        // Migrate from legacy internal home to external storage if the new dir is empty
        val legacy = File(sandboxDir, "home")
        val newHomeIsEmpty = target.listFiles().isNullOrEmpty()
        if (legacy.isDirectory && legacy.absolutePath != target.absolutePath && newHomeIsEmpty) {
            try {
                legacy.listFiles()?.forEach { entry ->
                    val dest = File(target, entry.name)
                    if (!dest.exists()) entry.copyRecursively(dest, overwrite = false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Legacy home migration failed: ${e.message}")
            }
        }
        target.absolutePath
    }

    val tmpPath: String get() = File(sandboxDir, "tmp").absolutePath

    // prootPath points to the system nativeLibraryDir where Android installs
    // .so files from the APK — this partition is always mounted exec.
    val prootPath: String get() = File(systemNativeLibDir, "libproot.so").absolutePath
    val nativeLibDir: String get() = systemNativeLibDir

    private val downloader = RootfsDownloader(OkHttpClient())

    init {
        checkExistingInstallation()
    }

    private fun checkExistingInstallation() {
        val rootfs = File(sandboxDir, "rootfs")
        val proot = File(prootPath)
        if (rootfs.isDirectory && proot.exists() && proot.canExecute()) {
            _state.value = SandboxState.Ready
        }
    }

    private fun getLinuxArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "aarch64"
        }
    }

    fun setup() {
        if (currentJob?.isActive == true) return
        currentJob = scope.launch {
            try {
                setupInternal()
            } catch (e: kotlinx.coroutines.CancellationException) {
                checkExistingInstallation()
            } catch (e: Exception) {
                val recoverable = e !is SandboxSetupException || e.recoverable
                _state.value = SandboxState.Error(e.message ?: "Setup failed", recoverable)
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        File(sandboxDir, "rootfs.tar.gz").delete()
        val rootfs = File(sandboxDir, "rootfs")
        if (rootfs.isDirectory && File(prootPath).exists()) {
            _state.value = SandboxState.Ready
        } else {
            _state.value = SandboxState.NotInstalled
        }
    }

    private suspend fun setupInternal() {
        val arch = getLinuxArch()

        sandboxDir.mkdirs()
        File(sandboxDir, "tmp").mkdirs()

        // Bail early if disk is too low — Alpine rootfs + packages need ~300MB.
        val stat = StatFs(sandboxDir.absolutePath)
        val availableMB = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
        } else {
            @Suppress("DEPRECATION")
            stat.availableBlocks.toLong() * stat.blockSize.toLong() / (1024 * 1024)
        }
        if (availableMB < MIN_DISK_SPACE_MB) {
            throw IllegalStateException(
                "Insufficient disk space: ${availableMB}MB available, ${MIN_DISK_SPACE_MB}MB required",
            )
        }

        extractProotBinaries()

        val proot = File(prootPath)
        if (!proot.exists()) {
            throw IllegalStateException(
                "Proot binary not found at $prootPath. " +
                    "sandboxDir contents: ${sandboxDir.listFiles()?.map { it.name } ?: "empty"}",
            )
        }

        // proot expects libtalloc.so.2 at runtime, but the APK has libtalloc.so.
        copyLibtalloc()

        val rootfsDir = File(sandboxDir, "rootfs")
        if (!rootfsDir.isDirectory) {
            val tarGzFile = File(sandboxDir, "rootfs.tar.gz")
            try {
                _state.value = SandboxState.Downloading(0f)
                downloader.download(arch, tarGzFile) { progress ->
                    _state.value = SandboxState.Downloading(progress)
                }

                _state.value = SandboxState.Extracting
                downloader.extractTarGz(tarGzFile, rootfsDir)
            } finally {
                // Keep tarball for retry if extraction failed (rootfs dir missing).
                if (rootfsDir.isDirectory) tarGzFile.delete()
            }
        }

        // Make rootfs writable before fixing executable bits — the hardlink
        // creation in fixExecutableBits needs write access to the rootfs.
        _state.value = SandboxState.Installing("Configuring...")
        downloader.makeWritable(rootfsDir)

        // Always fix executable bits — they can be lost during initial
        // extraction (hardlink ordering) or after an app update wipes them.
        // Safe to run on retry even when rootfs already exists.
        fixExecutableBits(rootfsDir)

        // Verify critical binaries survived extraction. A truncated download
        // or disk-full during extraction can leave the rootfs incomplete.
        verifyRootfsHealth(rootfsDir)
        downloader.writeResolvConf(rootfsDir)

        val executor = createProotExecutor()
        var updated = false
        for (mirror in downloader.mirrors) {
            downloader.writeRepositories(rootfsDir, mirror)
            val result = executor.execute("apk update", timeoutSeconds = 60)
            val stdout = result["stdout"] as? String ?: ""
            val stderr = result["stderr"] as? String ?: ""
            val hasRealError = stdout.lines().any { it.startsWith("ERROR:") } ||
                stderr.lines().any { it.startsWith("ERROR:") }
            val timedOut = result["timed_out"] as? Boolean ?: false
            val execError = result["error"] as? String
            if (execError != null || hasRealError || timedOut) continue
            updated = true
            break
        }
        if (!updated) {
            throw IllegalStateException("apk update failed on all Alpine mirrors")
        }

        _state.value = SandboxState.Ready
    }

    /**
     * Ensures critical shell binaries in the rootfs exist and are executable.
     *
     * The pure-Kotlin TAR extractor processes entries sequentially. Alpine's
     * minirootfs TAR places hardlink entries (e.g. /bin/sh → busybox) *before*
     * the real busybox entry. Since the fallback copy in extractTar silently
     * skips hardlinks whose target hasn't been extracted yet, files like
     * /bin/sh may be entirely MISSING after extraction — not just missing
     * their exec bit. This method:
     *
     * 1. Creates missing hardlinks (e.g. /bin/sh) by copying from busybox.
     * 2. Fixes exec bits on all files in bin/, sbin/, usr/bin/, usr/sbin/.
     */
    private fun fixExecutableBits(rootfsDir: File) {
        // Ensure rootfs is writable before creating hardlinks — installPackages()
        // and installGoogleWorkspaceCli() call this without makeWritable(), so after
        // app updates or apk operations reset permissions, ensureHardlink would fail.
        downloader.makeWritable(rootfsDir)

        // Ensure critical hardlinks exist — safety net for any hardlink the
        // TAR extractor may have skipped (Alpine minirootfs uses hardlinks
        // extensively: bin/sh, bin/ls, etc. are all hardlinks to busybox).
        val criticalHardlinks = listOf(
            "bin/sh", "bin/ls", "bin/cat", "bin/echo", "bin/grep",
            "bin/head", "bin/tail", "bin/wc", "bin/xargs",
            "usr/bin/env",
        )
        for (rel in criticalHardlinks) {
            ensureHardlink(rootfsDir, rel, "bin/busybox")
        }

        // Paths known to need executable bits in Alpine minirootfs.
        val execPaths = listOf(
            "bin/busybox",
            "bin/sh", "bin/ls", "bin/cat", "bin/echo", "bin/grep",
            "usr/bin/env",
            "usr/bin/awk",
            "sbin/apk",
        )
        for (rel in execPaths) {
            val f = File(rootfsDir, rel)
            if (f.exists() && f.isFile && !f.canExecute()) {
                if (!f.setExecutable(true, false)) {
                    Log.w(TAG, "fixExecutableBits: setExecutable failed for $rel")
                }
            }
        }
        // Also fix any file in bin/, sbin/, usr/bin/, usr/sbin/ that is a
        // regular file but not yet executable — covers all busybox applets
        // copied as fallback hardlinks.
        for (dir in listOf("bin", "sbin", "usr/bin", "usr/sbin")) {
            val dirFile = File(rootfsDir, dir)
            if (!dirFile.isDirectory) continue
            dirFile.listFiles()?.forEach { f ->
                if (f.isFile && !f.canExecute()) {
                    Log.d(TAG, "fixExecutableBits: fixing $dir/${f.name}")
                    f.setExecutable(true, false)
                }
            }
        }
    }

    /**
     * Checks that critical binaries exist after extraction. A truncated
     * download or disk-full during extraction can leave the rootfs broken.
     * Deletes the rootfs dir so the next setup() re-downloads.
     */
    private fun verifyRootfsHealth(rootfsDir: File) {
        val critical = listOf(
            "bin/sh",
            "bin/busybox",
            "sbin/apk",
            "usr/bin/env"
        )

        val missing = critical.filter { name ->
            val file = File(rootfsDir, name)

            try {
                val stat = android.system.Os.lstat(file.absolutePath)

                when {
                    // A symlink exists as a valid rootfs entry.
                    android.system.OsConstants.S_ISLNK(stat.st_mode) -> false

                    // Normal file must not be empty.
                    android.system.OsConstants.S_ISREG(stat.st_mode) ->
                        file.length() == 0L

                    else -> false
                }
            } catch (_: Exception) {
                // lstat failed = entry genuinely does not exist.
                true
            }
        }

        if (missing.isNotEmpty()) {
            Log.e(TAG, "Rootfs integrity check failed — missing: $missing")

            rootfsDir.deleteRecursively()

            throw IllegalStateException(
                "Rootfs is corrupt (missing: ${missing.joinToString()}). Re-downloading."
            )
        }
    }

    /**
     * Ensures [linkRel] exists as a copy of [targetRel]. Always overwrites
     * to handle broken placeholders, zero-byte files, or stale data from
     * TAR hardlink ordering issues.
     */
    private fun ensureHardlink(rootfsDir: File, linkRel: String, targetRel: String) {
        val targetFile = File(rootfsDir, targetRel)
        val linkFile = File(rootfsDir, linkRel)

        if (!targetFile.exists() || targetFile.length() == 0L) {
            Log.w(TAG, "ensureHardlink: target $targetRel is missing or empty")
            return
        }

        try {
            // lstat checks the directory entry itself and DOES NOT follow symlinks.
            val stat = try {
                android.system.Os.lstat(linkFile.absolutePath)
            } catch (_: Exception) {
                null
            }

            if (stat != null) {
                val isSymlink =
                    android.system.OsConstants.S_ISLNK(stat.st_mode)

                if (isSymlink) {
                    // Absolute Linux symlinks such as /bin/busybox appear broken
                    // from Android but are valid once running inside PRoot.
                    Log.d(TAG, "ensureHardlink: $linkRel is a symlink; preserving it")
                    return
                }

                // Keep a real non-empty extracted file.
                if (linkFile.isFile && linkFile.length() > 0L) {
                    return
                }

                // Remove zero-byte hardlink placeholder.
                if (!linkFile.delete()) {
                    Log.w(TAG, "ensureHardlink: could not remove placeholder $linkRel")
                    return
                }
            }

            linkFile.parentFile?.mkdirs()

            // Copy BusyBox only when the entry is genuinely missing or an empty
            // placeholder. This avoids Android host-side symlink resolution.
            targetFile.inputStream().use { input ->
                FileOutputStream(linkFile).use { output ->
                    input.copyTo(output)
                }
            }

            linkFile.setExecutable(true, false)

            Log.d(TAG, "ensureHardlink: restored $linkRel from $targetRel")
        } catch (e: Exception) {
            Log.e(TAG, "ensureHardlink: failed for $linkRel", e)
        }
    }

    private fun copyLibtalloc() {
        val tallocTarget = File(sandboxDir, "libtalloc.so.2")
        if (tallocTarget.exists()) return
        // Source from nativeLibraryDir (where Android installs APK .so files).
        val source = File(systemNativeLibDir, "libtalloc.so")
        if (source.exists()) {
            source.copyTo(tallocTarget, overwrite = true)
        } else {
            Log.w(TAG, "libtalloc.so not found in nativeLibraryDir, skipping libtalloc.so.2 copy")
        }
    }

    private fun extractProotBinaries() {
        // The primary proot binaries (libproot.so, libproot-loader.so,
        // libproot-loader32.so, libtalloc.so) are installed by Android's
        // package manager into nativeLibraryDir, which is always on an
        // exec-mounted partition. We do NOT need to extract them.
        //
        // Verify they exist so we give a clear error rather than a cryptic
        // "execve Permission denied" later.
        val proot = File(systemNativeLibDir, "libproot.so")
        if (!proot.exists()) {
            throw SandboxSetupException(
                "libproot.so not found in nativeLibraryDir ($systemNativeLibDir). " +
                    "Contents: ${File(systemNativeLibDir).listFiles()?.map { it.name } ?: "empty"}. " +
                    "Ensure the APK was built with extractNativeLibs=true or the .so files " +
                    "are present in jniLibs/.",
                recoverable = false,
            )
        }
        if (!proot.canExecute()) {
            throw SandboxSetupException(
                "libproot.so is not executable at ${proot.absolutePath}. " +
                    "This is unexpected for nativeLibraryDir — SELinux may be overly restrictive.",
                recoverable = false,
            )
        }
        Log.d(TAG, "proot binaries verified in nativeLibraryDir: $systemNativeLibDir")
    }

    /** Creates a one-shot [ProotExecutor] tied to the current sandbox paths. */
    fun createProotExecutor(): ProotExecutor = ProotExecutor(
        prootPath = prootPath,
        libDir = systemNativeLibDir,
        tallocDir = sandboxDir.absolutePath,
        rootfsPath = rootfsPath,
        homePath = homePath,
        tmpPath = tmpPath,
    )

    // ── Session shells ──────────────────────────────────────────────────────────

    private val shells = mutableMapOf<String, SessionShell>()
    private val _sessions = MutableStateFlow<List<String>>(emptyList())
    val sessions: StateFlow<List<String>> = _sessions

    private val pendingSaves = mutableMapOf<String, Job>()

    /**
     * Returns the [SessionShell] for [sessionId], creating it on first call.
     * Each conversation ID gets its own bash process so state doesn't bleed.
     */
    fun shellFor(sessionId: String): SessionShell = synchronized(shells) {
        shells[sessionId]?.let { return it }
        val inner = PersistentSandboxShell(createProotExecutor(), tmpPath)
        val wrapper = SessionShell(sessionId, inner)
        shells[sessionId] = wrapper
        _sessions.value = shells.keys.toList()
        wrapper
    }

    fun closeShell(sessionId: String) {
        val removed = synchronized(shells) {
            val s = shells.remove(sessionId)
            _sessions.value = shells.keys.toList()
            s
        }
        removed?.reset()
    }

    private fun closeAllShells() {
        val all = synchronized(shells) {
            val snapshot = shells.values.toList()
            shells.clear()
            _sessions.value = emptyList()
            snapshot
        }
        all.forEach { it.reset() }
    }

    // ── Package installation ────────────────────────────────────────────────────

    fun installPackages() {
        if (currentJob?.isActive == true) return
        val packages = listOf(
            "bash", "curl", "wget", "git", "jq", "python3", "py3-pip", "nodejs",
            "openssh-client", "lftp", "rsync",
        )
        currentJob = scope.launch {
            try {
                val rootfsDir = File(sandboxDir, "rootfs")
                // Re-apply executable bits before running apk — app updates or
                // previous partial installs may have left binaries non-executable
                // or missing hardlinks (e.g. /bin/sh → busybox).
                fixExecutableBits(rootfsDir)
                val executor = createProotExecutor()
                for (pkg in packages) {
                    ensureActive()
                    _state.value = SandboxState.Installing("Installing $pkg...")
                    val result = executor.execute("apk add --no-cache $pkg", timeoutSeconds = 120)
                    ensureActive()
                    val stderr = result["stderr"] as? String ?: ""
                    val stdout = result["stdout"] as? String ?: ""
                    val error = result["error"] as? String
                    val timedOut = result["timed_out"] as? Boolean ?: false
                    val exitCode = result["exit_code"] as? Int ?: -1
                    val hasRealError = stdout.lines().any { it.startsWith("ERROR:") } ||
                        stderr.lines().any { it.startsWith("ERROR:") }
                    if (error != null || hasRealError || timedOut || exitCode != 0) {
                        Log.e(TAG, "Failed to install $pkg: exit=$exitCode timedOut=$timedOut error=${error ?: "none"} stdout=$stdout stderr=$stderr")
                        val detail = stderr.ifEmpty {
                            error?.take(200) ?: stdout.take(200).ifEmpty { "exit code $exitCode" }
                        }.take(200)
                        _state.value = SandboxState.Error("Failed to install $pkg: $detail")
                        return@launch
                    }
                }
                _state.value = SandboxState.Ready
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.value = SandboxState.Ready
            } catch (e: Exception) {
                Log.e(TAG, "Package install exception", e)
                _state.value = SandboxState.Error("Install failed: ${e.message}")
            }
        }
    }

    fun installGoogleWorkspaceCli() {
        scope.launch {
            try {
                val rootfsDir = File(sandboxDir, "rootfs")
                fixExecutableBits(rootfsDir)
                val executor = createProotExecutor()

                val checkResult = executor.execute("which gws", timeoutSeconds = 10)
                val hasGws = checkResult["exit_code"] == 0 && checkResult["timed_out"] != true
                if (hasGws) {
                    _state.value = SandboxState.Ready
                    return@launch
                }

                _state.value = SandboxState.Installing("Installing Node.js and npm...")
                val nodeResult = executor.execute("apk add --no-cache nodejs npm", timeoutSeconds = 120)
                val nodeStderr = nodeResult["stderr"] as? String ?: ""
                val nodeStdout = nodeResult["stdout"] as? String ?: ""
                val nodeError = nodeResult["error"] as? String
                val nodeTimedOut = nodeResult["timed_out"] as? Boolean ?: false
                val nodeExitCode = nodeResult["exit_code"] as? Int ?: -1
                val nodeHasRealError = nodeStdout.lines().any { it.startsWith("ERROR:") } ||
                    nodeStderr.lines().any { it.startsWith("ERROR:") }
                if (nodeError != null || nodeHasRealError || nodeTimedOut || nodeExitCode != 0) {
                    val detail = nodeStderr.ifEmpty {
                        nodeError?.take(200) ?: nodeStdout.take(200).ifEmpty { "exit code $nodeExitCode" }
                    }.take(200)
                    _state.value = SandboxState.Error("Failed to install Node.js/npm: $detail")
                    return@launch
                }

                _state.value = SandboxState.Installing("Installing Google Workspace CLI...")
                val gwsResult = executor.execute("npm install -g @googleworkspace/cli", timeoutSeconds = 120)
                val gwsStderr = gwsResult["stderr"] as? String ?: ""
                val gwsStdout = gwsResult["stdout"] as? String ?: ""
                val gwsError = gwsResult["error"] as? String
                val gwsTimedOut = gwsResult["timed_out"] as? Boolean ?: false
                val gwsExitCode = gwsResult["exit_code"] as? Int ?: -1
                val gwsHasRealError = gwsStdout.lines().any { it.startsWith("ERROR:") } ||
                    gwsStderr.lines().any { it.startsWith("ERROR:") }
                if (gwsError != null || gwsHasRealError || gwsTimedOut || gwsExitCode != 0) {
                    val detail = gwsStderr.ifEmpty {
                        gwsError?.take(200) ?: gwsStdout.take(200).ifEmpty { "exit code $gwsExitCode" }
                    }.take(200)
                    _state.value = SandboxState.Error("Failed to install Google Workspace CLI: $detail")
                    return@launch
                }

                _state.value = SandboxState.Ready
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.value = SandboxState.Ready
            } catch (e: Exception) {
                Log.e(TAG, "Google Workspace CLI install exception", e)
                _state.value = SandboxState.Error("GWS install failed: ${e.message}")
            }
        }
    }

    fun isGoogleWorkspaceCliInstalled(): Boolean {
        if (_state.value !is SandboxState.Ready) return false
        return File(rootfsPath, "usr/local/bin/gws").exists() ||
            File(rootfsPath, "usr/bin/gws").exists()
    }

    fun getGoogleWorkspaceCliPath(): String? {
        val local = File(rootfsPath, "usr/local/bin/gws")
        if (local.exists()) return local.absolutePath
        val usr = File(rootfsPath, "usr/bin/gws")
        if (usr.exists()) return usr.absolutePath
        return null
    }

    /** Deletes all sandbox data and resets state to [SandboxState.NotInstalled]. */
    fun reset() {
        scope.launch {
            closeAllShells()
            sandboxDir.deleteRecursively()
            _state.value = SandboxState.NotInstalled
        }
    }

    /** Returns disk usage of the sandbox directory in MB. */
    fun getDiskUsageMB(): Long {
        if (!sandboxDir.isDirectory) return 0
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(sandboxDir)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: continue
            for (child in children) {
                try {
                    when {
                        child.isDirectory -> stack.addLast(child)
                        child.isFile -> total += child.length()
                    }
                } catch (_: Throwable) { /* skip transient entry */ }
            }
        }
        return total / (1024 * 1024)
    }

    /** Returns true when the common developer packages are present on disk. */
    fun arePackagesInstalled(): Boolean {
        if (_state.value !is SandboxState.Ready) return false
        return File(rootfsPath, "usr/bin/python3").exists() &&
            File(rootfsPath, "usr/bin/ssh").exists()
    }
}
