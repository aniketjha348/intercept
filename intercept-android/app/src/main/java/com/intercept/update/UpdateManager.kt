package com.intercept.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

/** Downloads the APK from /app/latest and fires the installer.
 *  System asks the user for unknown-sources permission on first use. */
object UpdateManager {
    private const val FILE_NAME = "intercept-update.apk"

    fun download(context: Context, url: String): Long {
        val dm = context.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("INTERCEPT update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
            .setMimeType("application/vnd.android.package-archive")
        // Clear any stale file from a previous attempt.
        try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME).delete()
        } catch (_: Exception) {
        }
        return dm.enqueue(req)
    }

    fun install(context: Context, downloadId: Long) {
        val dm = context.getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return
        // Re-share through FileProvider so the installer can read it on API 24+.
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
        val contentUri = if (file.exists()) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            uri
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
