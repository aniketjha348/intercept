package com.intercept.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.intercept.MainActivity
import com.intercept.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Zero-paste net for other apps' messages. WhatsApp/Telegram expose no API
 * (E2E encrypted — NOBODY can read them directly, not even Truecaller), so we
 * scan what arrives as notifications: message text in → risk check → warning
 * out, all automatic. Muted chats produce no notification and can't be seen —
 * that is an OS limit, not a bug. Our own alerts are never re-scanned.
 */
class InterceptNotifications : NotificationListenerService() {

    companion object {
        // Every chat app whose message text Android shows in notifications.
        // No API, no root, no paste — the OS hands us the text, we judge it.
        private val CHAT_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thunderdog.challegram", // Telegram X
            "com.facebook.orca", // Messenger
            "org.signal.private_messenger",
            "com.instagram.android",
            "com.google.android.apps.messaging", // SMS/RCS overflow
            "com.discord",
            "com.snapchat.android",
        )
        private const val ALERT_CHANNEL = "intercept_alert"
        private const val RISK_THRESHOLD = 25
        private const val COOLDOWN_MS = 10 * 60 * 1000L
        private val lastAlert = mutableMapOf<String, Long>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            if (pkg == packageName) return // never scan ourselves (loop guard)
            if (pkg !in CHAT_APPS) return
            val container = try {
                applicationContext.appContainer()
            } catch (_: Exception) {
                return
            }
            if (!container.autoApps) return
            val extras = sbn.notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()).orEmpty()
            if (text.isBlank() || text.length < 4) return
            // Incoming internet-call ring (WhatsApp/Telegram call screen posts one):
            // we cannot answer it (OS lock), so raise a caution instead.
            if (isCallRing(title, text)) {
                caution(title.ifBlank { "Internet call" })
                return
            }
            // Skip delivery summaries ("2 new messages") — no content to judge.
            if (text.matches(Regex("\\d+ new messages?")) || title.isBlank()) return
            val key = "$pkg|$title"
            val now = System.currentTimeMillis()
            if (now - (lastAlert[key] ?: 0L) < COOLDOWN_MS) return
            scope.launch {
                try {
                    val result = container.repo.analyzeText("$title: $text", "WHATSAPP")
                    if (result.risk >= RISK_THRESHOLD) {
                        lastAlert[key] = now
                        warn(title, result.level.label, result.risk,
                            result.simple.ifEmpty { result.userMessage })
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun isCallRing(title: String, text: String): Boolean {
        val both = "$title $text".lowercase()
        return both.contains("incoming") && (both.contains("call") || both.contains("ringing")) ||
            both.contains("whatsapp voice call") || both.contains("whatsapp video call")
    }

    private fun caution(title: String) {
        notify(
            id = ("call$title").hashCode(),
            heading = "📞 $title — stay sharp",
            body = "Internet calls can't be auto-screened by any app. If they demand OTP, money or codes — hang up, then verify the person separately.",
            fullScreen = true, // unmissable, like a real incoming-call screen
        )
    }

    private fun warn(sender: String, level: String, risk: Int, why: String) {
        notify(
            id = ("wa$sender$risk").hashCode(),
            heading = "⚠ Risky message from $sender — $level $risk",
            body = why.ifBlank { "Tap to open INTERCEPT and see why this was flagged." },
        )
    }

    private fun notify(id: Int, heading: String, body: String, fullScreen: Boolean = false) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL, "Threat alerts", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                this, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(heading)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
            if (fullScreen) {
                builder.setCategory(NotificationCompat.CATEGORY_CALL)
                builder.setFullScreenIntent(pi, true)
            }
            nm.notify(id, builder.build())
        } catch (_: Exception) {
        }
    }

}
