/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_OUTPUT_LENGTH = 15_000
private const val DEFAULT_TIMEOUT_SECONDS = 30L
private const val MAX_TIMEOUT_SECONDS = 10800L  // 3 hours for long installations

/**
 * Handle to a streaming proot process.
 *
 * Allows the caller to write stdin, cancel the process, and wait for exit.
 */
class ProotHandle internal constructor(
    private val process: Process,
    private val cancelled: AtomicBoolean,
    private val readerFutures: List<CompletableFuture<Void>>,
) {
    fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        process.destroyForcibly()
    }

    fun writeInput(line: String) {
        if (cancelled.get()) return
        runCatching {
            val bytes = (line + "\n").toByteArray()
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }
    }

    fun awaitExit(): Int {
        // Poll so a cancel() from another thread can short-circuit the wait.
        // On Linux, close(fd) does NOT unblock a thread already inside read(fd),
        // so reader futures can sit waiting on a tracee pipe even after SIGKILL.
        while (!cancelled.get() && process.isAlive) {
            runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }
        }
        if (cancelled.get()) return -1
        readerFutures.forEach { runCatching { it.get(500, TimeUnit.MILLISECONDS) } }
        return runCatching { process.exitValue() }.getOrDefault(-1)
    }
}

/**
 * Executes commands inside the Alpine Linux rootfs using the proot binary
 * extracted to nativeLibraryDir.
 *
 * On Android the app data partition is sometimes mounted noexec or SELinux
 * blocks direct execution of binaries under /data/data/. To work around this,
 * [ANDROID_LINKER] is prepended to the exec argv — the dynamic linker can
 * mmap(PROT_EXEC) the .so file even from a noexec filesystem. This matches
 * how Termux and other Android-native Linux environments run their binaries.
 */
class ProotExecutor(
    private val prootPath: String,
    private val libDir: String,
    private val tallocDir: String,
    private val rootfsPath: String,
    private val homePath: String,
    private val tmpPath: String,
) {

    /** Handle to the most recently spawned process, if any. */
    @Volatile private var currentProcess: Process? = null

    /** Forcibly kill the current running process (if any). Thread-safe. */
    fun cancelCurrent() {
        currentProcess?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    /**
     * Records [process] as the cancel target, unless a newer process has
     * already claimed the slot. Ensures [cancelCurrent] never kills an
     * unrelated newer execution.
     */
    private fun claimCurrentProcess(process: Process) {
        val existing = currentProcess
        if (existing == null || !existing.isAlive) {
            currentProcess = process
        }
    }

    companion object {
        /**
         * Android dynamic linker path, or null if not found (fallback to direct
         * execution). Detected once per process.
         */
        private val ANDROID_LINKER: String? by lazy {
            // 64-bit first — most modern Android devices.
            val candidates = listOf("/system/bin/linker64", "/system/bin/linker")
            candidates.firstOrNull { File(it).exists() }
        }

        private const val ENV_KEY_MAX_LENGTH = 128
        private val DENYLISTED_ENV_KEYS =
            setOf(
                "LD_PRELOAD",
                "LD_LIBRARY_PATH",
                "LD_DEBUG",
                "LD_AUDIT",
                "LD_ORIGIN_PATH",
                "LD_USE_LOAD_BIAS",
                "LD_BIND_NOT",
                "LD_PROFILE",
                "LD_NOWARN",
                "LD_HELP",
                "LD_TRACE_LOADED_OBJECTS",
                "ANDROID_DNS_MODE",
            )
    }

    fun execute(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
    ): Map<String, Any> {
        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)

        return try {
            val process = Runtime.getRuntime().exec(
                buildProcessArgs(command, workingDir),
                buildEnvVars(extraEnv),
                File(rootfsPath).parentFile,
            )
            claimCurrentProcess(process)

            // Drain stdout/stderr concurrently to avoid pipe buffer deadlock
            val stdoutFuture = CompletableFuture.supplyAsync {
                readBounded(process.inputStream.bufferedReader())
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                readBounded(process.errorStream.bufferedReader())
            }

            val completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return mapOf(
                    "success" to false,
                    "stdout" to stdoutFuture.get(1, TimeUnit.SECONDS).smartTruncate(MAX_OUTPUT_LENGTH),
                    "stderr" to stderrFuture.get(1, TimeUnit.SECONDS).smartTruncate(MAX_OUTPUT_LENGTH),
                    "exit_code" to -1,
                    "timed_out" to true,
                )
            }

            mapOf(
                "success" to (process.exitValue() == 0),
                "stdout" to stdoutFuture.get().smartTruncate(MAX_OUTPUT_LENGTH),
                "stderr" to stderrFuture.get().smartTruncate(MAX_OUTPUT_LENGTH),
                "exit_code" to process.exitValue(),
                "timed_out" to false,
            )
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to (e.message ?: "Failed to execute command in sandbox"),
            )
        }
    }

    fun executeStreaming(
        command: String,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): ProotHandle {
        val process = Runtime.getRuntime().exec(
            buildProcessArgs(command, workingDir),
            buildEnvVars(extraEnv),
            File(rootfsPath).parentFile,
        )
        val cancelled = AtomicBoolean(false)
        val stdoutFuture = CompletableFuture.runAsync {
            streamLines(process.inputStream.bufferedReader(), cancelled, onStdout)
        }
        val stderrFuture = CompletableFuture.runAsync {
            streamLines(process.errorStream.bufferedReader(), cancelled, onStderr)
        }
        return ProotHandle(process, cancelled, listOf(stdoutFuture, stderrFuture))
    }

    private fun buildProcessArgs(command: String, workingDir: String): Array<String> {
        val prootArgs = arrayOf(
            prootPath,
            "--rootfs=$rootfsPath",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=$homePath:/root",
            "--bind=$tmpPath:/tmp",
            "-0",
            "-w", workingDir,
            "/bin/sh", "-c", command,
        )
        val linker = ANDROID_LINKER
        return if (linker != null) {
            arrayOf(linker, *prootArgs)
        } else {
            prootArgs
        }
    }

    private fun buildEnvVars(extraEnv: Map<String, String>): Array<String> {
        val libDirFile = File(prootPath).parentFile ?: File(libDir)
        val loaderPath = File(libDirFile, "libproot-loader.so").absolutePath
        val loader32Path = File(libDirFile, "libproot-loader32.so").absolutePath
        // Include both the native lib dir (proot-loader) and the talloc dir
        // (libtalloc.so.2 symlink) in LD_LIBRARY_PATH.
        val ldLibPath = if (tallocDir != libDir) "$libDir:$tallocDir" else libDir
        val baseEnv = arrayOf(
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LD_LIBRARY_PATH=$ldLibPath",
            "PROOT_TMP_DIR=$tmpPath",
            "PROOT_LOADER=$loaderPath",
            "PROOT_LOADER_32=$loader32Path",
        )
        return baseEnv + sanitizeExtraEnv(extraEnv).map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    /**
     * Filters model-controlled environment variables before they reach the
     * process environment.
     *
     * The sandbox bridge accepts an `env` object from the agent; without
     * filtering, a malicious key like `LD_PRELOAD=/path/to/evil.so` would be
     * honoured by the Android dynamic linker running proot *inside the app's
     * host process*, giving the agent code execution in the app's UID.
     * Keys that could clobber the proot runtime, inject libraries, or smuggle
     * extra arguments are rejected.
     */
    private fun sanitizeExtraEnv(extraEnv: Map<String, String>): Map<String, String> =
        extraEnv
            .filterKeys { key -> isEnvKeyAllowed(key) }
            .filterValues { value -> !value.contains('\u0000') }

    private fun isEnvKeyAllowed(key: String): Boolean {
        if (key.isEmpty() || key.length > ENV_KEY_MAX_LENGTH) return false
        if (key.any { it == '=' || it == '\n' || it == '\u0000' || it.isWhitespace() }) return false
        if (key in DENYLISTED_ENV_KEYS) return false
        if (key.startsWith("PROOT_")) return false
        return true
    }

    private fun readBounded(reader: BufferedReader): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        try {
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                sb.append(buf, 0, read)
                if (sb.length >= MAX_OUTPUT_LENGTH) break
            }
            if (sb.length >= MAX_OUTPUT_LENGTH) {
                while (reader.read(buf) != -1) { /* discard */ }
            }
        } catch (_: IOException) {
            // Stream closed under us (typically destroyForcibly on timeout).
        }
        return sb.toString()
    }

    private fun streamLines(
        reader: BufferedReader,
        cancelled: AtomicBoolean,
        onLine: (String) -> Unit,
    ) {
        try {
            while (!cancelled.get()) {
                val line = try {
                    reader.readLine()
                } catch (e: IOException) {
                    if (cancelled.get()) break
                    throw e
                } ?: break
                onLine(line)
            }
        } finally {
            runCatching { reader.close() }
        }
    }
}

/** Truncates a string at [maxLength], preferring to cut at a newline boundary. */
private fun String.smartTruncate(maxLength: Int): String {
    if (length <= maxLength) return this
    val cut = lastIndexOf('\n', maxLength)
    return if (cut > maxLength / 2) substring(0, cut) else substring(0, maxLength)
}
