/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "RootfsDownloader"
private const val ALPINE_VERSION = "3.21.3"
private const val ALPINE_BRANCH = "v3.21"
private const val BUFFER_SIZE = 8192

private val ALPINE_MIRRORS = listOf(
    "https://dl-cdn.alpinelinux.org/alpine",
    "https://mirrors.edge.kernel.org/alpine",
    "https://ftp.halifax.rwth-aachen.de/alpine",
    "https://alpine.ethz.ch/alpine",
    "https://mirror.csclub.uwaterloo.ca/alpine",
    "https://mirrors.tuna.tsinghua.edu.cn/alpine",
)
private const val TAR_BLOCK_SIZE = 512
private const val TAR_NAME_OFFSET = 0
private const val TAR_MODE_OFFSET = 100
private const val TAR_SIZE_OFFSET = 124
private const val TAR_TYPE_OFFSET = 156
private const val TAR_LINK_OFFSET = 157
private const val TAR_PREFIX_OFFSET = 345

/**
 * Downloads the Alpine Linux minirootfs tarball from a mirror list and
 * extracts it without requiring native tar (uses a pure-Kotlin TAR parser).
 */
class RootfsDownloader(private val httpClient: OkHttpClient) {

    val mirrors: List<String> = ALPINE_MIRRORS

    fun getDownloadUrls(arch: String): List<String> = ALPINE_MIRRORS.map { base ->
        "$base/$ALPINE_BRANCH/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"
    }

    suspend fun download(
        arch: String,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val urls = getDownloadUrls(arch)
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            try {
                downloadFrom(url, targetFile, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (targetFile.exists()) targetFile.delete()
                if (index < urls.lastIndex) onProgress(0f)
            }
        }
        throw IOException("All Alpine mirrors failed", lastError)
    }

    private suspend fun downloadFrom(
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        val response = suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) = cont.resume(response)
            })
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $url")
            val body = resp.body ?: throw IOException("Empty response body from $url")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L
            var downloadedBytes = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            body.byteStream().use { stream ->
                FileOutputStream(targetFile).use { output ->
                    while (true) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead < 0) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) onProgress(downloadedBytes.toFloat() / totalBytes)
                    }
                }
            }
            // ponytail: server can close early with FIN — detect truncated download
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                targetFile.delete()
                throw IOException(
                    "Incomplete download from $url: expected $totalBytes bytes, got $downloadedBytes",
                )
            }
        }
    }

    fun extractTarGz(tarGzFile: File, targetDir: File) {
        targetDir.mkdirs()
        GZIPInputStream(BufferedInputStream(FileInputStream(tarGzFile))).use { gzipStream ->
            extractTar(gzipStream, targetDir)
        }
    }

    private fun extractTar(inputStream: java.io.InputStream, targetDir: File) {
        val headerBuffer = ByteArray(TAR_BLOCK_SIZE)
        val dataBuffer = ByteArray(BUFFER_SIZE)

        while (true) {
            val headerBytesRead = readFully(inputStream, headerBuffer)
            if (headerBytesRead < TAR_BLOCK_SIZE) break

            val name = readTarString(headerBuffer, TAR_NAME_OFFSET, 100)
            if (name.isEmpty()) break

            val prefix = readTarString(headerBuffer, TAR_PREFIX_OFFSET, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

            val sizeStr = readTarString(headerBuffer, TAR_SIZE_OFFSET, 12)
            val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L

            val modeStr = readTarString(headerBuffer, TAR_MODE_OFFSET, 8)
            val mode = if (modeStr.isNotEmpty()) modeStr.toInt(8) else 0
            val typeFlag = headerBuffer[TAR_TYPE_OFFSET]
            val linkName = readTarString(headerBuffer, TAR_LINK_OFFSET, 100)

            val outFile = File(targetDir, fullName)

            if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                skipBytes(inputStream, alignToBlock(size))
                continue
            }

            when (typeFlag.toInt().toChar()) {
                '5', 'D' -> outFile.mkdirs()

                '2' -> {
                    outFile.parentFile?.mkdirs()
                    try {
                        if (outFile.exists()) outFile.delete()
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(linkName),
                        )
                    } catch (e: Exception) {
                        // Symlink creation can fail on Android (no /proc/self/exe
                        // access or SELinux). Fall back: if linkName refers to an
                        // existing entry, copy it so the target path is not a hole.
                        val fallback = if (linkName.startsWith("/")) {
                            File(targetDir, linkName.substring(1))
                        } else {
                            File(outFile.parentFile, linkName)
                        }
                        if (fallback.exists()) {
                            fallback.copyTo(outFile, overwrite = true)
                            if (fallback.canExecute()) {
                                outFile.setExecutable(true, false)
                            }
                        }
                    }
                }

                '1' -> {
                    // Alpine hardlinks use relative names (e.g. "busybox" for bin/sh).
                    // Resolve relative to the link's parent dir, not the root.
                    val linkTarget = if (linkName.startsWith("/")) {
                        File(targetDir, linkName.substring(1))
                    } else {
                        File(outFile.parentFile, linkName)
                    }
                    outFile.parentFile?.mkdirs()
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                        if (linkTarget.canExecute()) {
                            outFile.setExecutable(true, false)
                        }
                    } else {
                        // Target not extracted yet — create a zero-byte placeholder.
                        // A later extraction pass or fixExecutableBits will replace it
                        // with the real content once the target exists.
                        outFile.createNewFile()
                    }
                }

                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        var remaining = size
                        while (remaining > 0) {
                            val toRead = minOf(remaining, dataBuffer.size.toLong()).toInt()
                            val bytesRead = inputStream.read(dataBuffer, 0, toRead)
                            if (bytesRead <= 0) break
                            output.write(dataBuffer, 0, bytesRead)
                            remaining -= bytesRead
                        }
                    }
                    if (mode and 0b001_001_001 != 0) {
                        outFile.setExecutable(true, false)
                    }
                    val padding = alignToBlock(size) - size
                    if (padding > 0) skipBytes(inputStream, padding)
                    continue
                }

                else -> {}
            }

            if (size > 0 && typeFlag.toInt().toChar() != '0' && typeFlag.toInt().toChar() != '\u0000') {
                skipBytes(inputStream, alignToBlock(size))
            }
        }
    }

    private fun readTarString(buffer: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, buffer.size)
        val nullIndex = (offset until end).firstOrNull { buffer[it] == 0.toByte() } ?: end
        return String(buffer, offset, nullIndex - offset, Charsets.US_ASCII).trim()
    }

    private fun readFully(inputStream: java.io.InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val bytesRead = inputStream.read(buffer, totalRead, buffer.size - totalRead)
            if (bytesRead <= 0) break
            totalRead += bytesRead
        }
        return totalRead
    }

    private fun skipBytes(inputStream: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = inputStream.skip(remaining)
            if (skipped <= 0) {
                if (inputStream.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun alignToBlock(size: Long): Long {
        val remainder = size % TAR_BLOCK_SIZE
        return if (remainder == 0L) size else size + (TAR_BLOCK_SIZE - remainder)
    }

    fun makeWritable(rootfsDir: File) {
        if (!rootfsDir.canWrite()) rootfsDir.setWritable(true, true)
        // ponytail: on EROFS or noexec mounts, setWritable fails for every file —
        // detect early and skip the noisy walk instead of logging N warnings.
        val probe = File(rootfsDir, "bin")
        if (probe.isDirectory && !probe.canWrite() && !probe.setWritable(true, true)) {
            Log.w(TAG, "makeWritable: rootfs appears read-only, skipping chmod (proot handles permissions)")
            return
        }
        rootfsDir.walkTopDown().forEach { file ->
            if (!file.canWrite()) {
                val ok = file.setWritable(true, true)
                if (!ok) Log.d(TAG, "makeWritable: could not chmod ${file.path}")
            }
        }
    }

    fun writeResolvConf(rootfsDir: File) {
        val etcDir = File(rootfsDir, "etc")
        etcDir.mkdirs()
        File(etcDir, "resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\n",
        )
    }

    fun writeRepositories(rootfsDir: File, mirrorBase: String) {
        val apkDir = File(rootfsDir, "etc/apk")
        apkDir.mkdirs()
        File(apkDir, "repositories").writeText(
            "$mirrorBase/$ALPINE_BRANCH/main\n$mirrorBase/$ALPINE_BRANCH/community\n",
        )
    }
}
