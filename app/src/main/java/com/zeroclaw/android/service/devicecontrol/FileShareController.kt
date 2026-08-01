package com.zeroclaw.android.service.devicecontrol

import android.content.Context
import android.content.Intent
import android.net.Uri

class FileShareController(private val context: Context) {
    fun share(contentUri: Uri, mimeType: String?, targetPackage: String?): Boolean = runCatching {
        require(contentUri.scheme == "content") {
            "device_control only accepts shareable content:// URIs, not raw filesystem paths"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!targetPackage.isNullOrBlank()) setPackage(targetPackage)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}