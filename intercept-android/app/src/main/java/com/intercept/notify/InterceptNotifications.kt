package com.intercept.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.intercept.MainActivity
import com.intercept.appContainer
import com.intercept.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Zero-paste net for other apps' messages. WhatsApp/Telegram expose no API
 * (E2E encrypted — NOBODY can read them directly, not even Truecaller), so we
 * scan what arrives as notifications: message text in → risk check → action
 * out, all automatic. Muted chats produce no notification and can't be seen —
 * that is an OS limit, not a bug. Our own alerts are never re-scanned.
 *
 * Two things happen when a message is judged dangerous, in this order:
 *  1. The link itself goes through the URL engine (a fake bank page is a link
 *     problem, and the text engine cannot see a domain the way /analyze/url
 *     can).
 *  2. Past [BLOCK_THRESHOLD] the notification is dismissed outright. We cannot
 *     stop another app from rendering a tappable URL — but we can take away the
 *     tap. Below that threshold the user keeps their message and just gets the
 *     warning: never silently eat a message we are only mildly suspicious of.
 */
class InterceptNotifications : NotificationListenerService() {

    companion object {
        // Every chat app whose message text Android shows in notifications.
        // No API, no root, no paste — the OS hands us the text, we judge it.
        private val CHAT_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thunderdog.challegram", // Telegram X
            "org.thoughtcrime.securesms", // Signal (the real package name)
            "com.facebook.orca", // Messenger
            "com.instagram.android", "com.instagram.lite",
            "com.google.android.apps.messaging", // SMS/RCS overflow
            "com.discord", "com.snapchat.android",
            "com.viber.voip", "com.slack", "com.microsoft.teams",
            "com.linkedin.android",
        )
        private const val ALERT_CHANNEL = "intercept_alert"
        private const val RISK_THRESHOLD = 25
        /** Past this, the notification itself goes away — not just a warning. */
        private const val BLOCK_THRESHOLD = 60
        private const val COOLDOWN_MS = 10 * 60 * 1000L
        private val lastAlert = mutableMapOf<String, Long>()
        private val URL_RE = Regex("""(?:https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE)
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
            val sbnKey = sbn.key
            scope.launch {
                try {
                    val body = "$title: $text"
                    var risk = 0
                    var level = "LOW"
                    var why = ""
                    var flagged: String? = null
                    // Links first, and independently: a "KYC update" that points
                    // at a lookalike bank domain must be caught even when the
                    // wording alone reads clean.
                    for (link in links(text).take(2)) {
                        val r = try {
                            container.repo.analyzeUrl(link, body)
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        if (r.risk > risk) {
                            risk = r.risk
                            level = r.level.label
                            why = r.simple.ifEmpty { r.userMessage }
                            flagged = link
                        }
                    }
                    if (risk < RISK_THRESHOLD) {
                        val result = container.repo.analyzeText(body, "WHATSAPP")
                        if (result.risk > risk) {
                            risk = result.risk
                            level = result.level.label
                            why = result.simple.ifEmpty { result.userMessage }
                            flagged = null
                        }
                    }
                    if (risk >= RISK_THRESHOLD) {
                        lastAlert[key] = now
                        val blocked = risk >= BLOCK_THRESHOLD
                        if (blocked) {
                            // The closest thing to "blocking" a link that Android
                            // permits: remove the notification that hands it over.
                            try {
                                cancelNotification(sbnKey)
                            } catch (_: Exception) {
                            }
                        }
                        warn(title, level, risk, why, flagged, blocked)
                        // Overlay banner over whatever the user is looking at,
                        // when the bubble is running (same process, no start call).
                        OverlayService.flash(
                            "⚠ " + (if (blocked) "Message removed — $title" else "Risky message from $title"),
                            why.ifEmpty { "$level $risk — tap to see why." },
                            flagged,
                        )
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun links(text: String): List<String> =
        URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '।') }.toList()

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

    private fun warn(
        sender: String,
        level: String,
        risk: Int,
        why: String,
        link: String?,
        blocked: Boolean,
    ) {
        notify(
            id = ("wa$sender$risk").hashCode(),
            heading = if (blocked) "🛡 Removed a risky message from $sender — $level $risk"
            else "⚠ Risky message from $sender — $level $risk",
            body = why.ifBlank { "Tap to open INTERCEPT and see why this was flagged." },
            // One tap lands in Analyze with this exact link already verifying.
            analyze = link,
        )
    }

    private fun notify(
        id: Int,
        heading: String,
        body: String,
        fullScreen: Boolean = false,
        analyze: String? = null,
    ) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL, "Threat alerts", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (analyze != null) {
                // Stashed before the activity launches, so AnalyzeScreen picks it
                // up exactly like the share-sheet path does.
                try {
                    applicationContext.appContainer().pendingSharedText = analyze
                } catch (_: Exception) {
                }
                intent.putExtra(MainActivity.EXTRA_ANALYZE, true)
            }
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
