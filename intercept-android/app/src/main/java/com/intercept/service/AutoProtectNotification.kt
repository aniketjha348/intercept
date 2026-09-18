package com.intercept.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.intercept.MainActivity

/**
 * The single ongoing notification for the whole protection stack.
 *
 * Two foreground services need a notification while they hold the foreground —
 * the always-on daemon and the call/SMS screener — but the user should read one
 * line, not two. Android permits this: foreground services may share a
 * notification id, and a service that is done can leave the foreground without
 * removing the notification (`STOP_FOREGROUND_DETACH`). So both services post
 * this id, each writes the text it knows about, and only the daemon keeps it
 * alive between jobs.
 *
 * One channel, one importance. IMPORTANCE_LOW is deliberate: visible in the
 * shade (the always-on promise has to be visible) and never audible. Note that
 * Android freezes a channel's importance at first creation, which is exactly why
 * this lives in one place instead of being re-declared by each service.
 */
object AutoProtectNotification {

    /** Shared by every foreground piece of the protection stack. */
    const val ID = 1001

    const val CHANNEL = "intercept_auto"

    const val IDLE_TEXT = "Watching calls, messages and links."

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Auto-protect", NotificationManager.IMPORTANCE_LOW)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * [watchSessionId] is set only while a call is being screened, so tapping the
     * same notification lands on the transcript exactly when there is one to read.
     */
    fun build(context: Context, text: String, watchSessionId: String? = null): Notification {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        watchSessionId?.takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(MainActivity.EXTRA_WATCH_SID, it) }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("INTERCEPT auto-protect")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    /** Rewrite the shared line (the caller is already in the foreground). */
    fun update(context: Context, text: String, watchSessionId: String? = null) {
        try {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(ID, build(context, text, watchSessionId))
        } catch (_: Exception) {
        }
    }
}
