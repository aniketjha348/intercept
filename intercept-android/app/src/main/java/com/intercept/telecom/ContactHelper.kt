package com.intercept.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Stranger check: only unknown numbers get auto-screened — saved contacts
 * always ring through normally. Without READ_CONTACTS we play safe and
 * treat everyone as known (no auto-answer) instead of answering mom's call.
 */
object ContactHelper {

    fun isUnknown(context: Context, number: String?): Boolean {
        if (number.isNullOrBlank() || number == "Unknown") return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
            )?.use { cursor ->
                !cursor.moveToFirst()
            } ?: true
        } catch (_: Exception) {
            true
        }
    }
}
