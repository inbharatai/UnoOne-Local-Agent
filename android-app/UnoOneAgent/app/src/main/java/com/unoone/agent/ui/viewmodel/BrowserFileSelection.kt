package com.unoone.agent.ui.viewmodel

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** Storage Access Framework adapter for WebView file inputs. No file path resolution or copies. */
internal object BrowserFileSelection {
    const val MAX_UPLOAD_BYTES: Long = 50L * 1024L * 1024L

    val SUPPORTED_MIME_TYPES = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/plain",
        "image/jpeg",
        "image/png"
    )
    val SUPPORTED_EXTENSIONS = setOf("pdf", "docx", "pptx", "xlsx", "txt", "jpg", "jpeg", "png")

    fun intent(acceptTypes: Array<String>, allowMultiple: Boolean): Intent {
        val requested = acceptTypes
            .flatMap { it.split(",") }
            .map(String::trim)
            .filter { it.contains("/") && it.isNotBlank() }
            .distinct()
            .ifEmpty { SUPPORTED_MIME_TYPES.toList() }
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (requested.size == 1) requested.first() else "*/*"
            if (requested.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, requested.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    fun uris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val ordered = LinkedHashSet<Uri>()
        data.data?.let(ordered::add)
        val clip: ClipData? = data.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(ordered::add)
        }
        return ordered.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
