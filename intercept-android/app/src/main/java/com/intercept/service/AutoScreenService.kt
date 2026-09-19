package com.intercept.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.intercept.MainActivity
import com.intercept.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Headless auto-protect engine, messages only. After the one-time setup,
 * stranger SMS are scanned silently and only a genuinely risky one raises an
 * alert.
 *
 * Calls are deliberately NOT handled here. Screening a call on device meant
 * answering it and playing the AI voice out of the owner's own earpiece — audio
 * a cellular uplink cannot carry, so the caller heard a screech and the AI never
 * really spoke to them. Unknown calls now reach the cloud agent through carrier
 * forwarding (see InterceptScreeningService), where the caller really does hear
 * the AI.
 */
class AutoScreenService : Service() {

    companion object {
        private const val ACTION_SMS = "com.intercept.action.SCREEN_SMS"
        private const val CHANNEL_ALERT = "intercept_alert"
        private const val SMS_RISK_THRESHOLD = 25

        fun screenSms(context: Context, sender: String, body: String) {
            val intent = Intent(context, AutoScreenService::class.java)
                .setAction(ACTION_SMS)
                .putExtra("sender", sender)
                .putExtra("body", body)
            run(intent, context)
        }

        private fun run(intent: Intent, context: Context) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The ongoing line is shared with the daemon (AutoProtectNotification);
        // only the alert channel belongs to this service alone.
        AutoProtectNotification.ensureChannel(this)
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Threat alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Same id as the daemon: one ongoing line for the whole stack.
        startForeground(
            AutoProtectNotification.ID,
            AutoProtectNotification.build(this, AutoProtectNotification.IDLE_TEXT),
        )
        when (intent?.action) {
            ACTION_SMS -> {
                val sender = intent.getStringExtra("sender").orEmpty()
                val body = intent.getStringExtra("body").orEmpty()
                scope.launch {
                    try {
                        handleSms(sender, body)
                    } finally {
                        releaseOngoing()
                        stopSelf(startId)
                    }
                }
            }
            else -> {
                releaseOngoing()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    // ---- SMS: silent scan, loud only when risky ----

    private suspend fun handleSms(sender: String, body: String) {
        if (body.isBlank()) return
        val container = try {
            appContainer()
        } catch (_: Exception) {
            return
        }
        val result = try {
            container.repo.analyzeText(body, "SMS")
        } catch (_: Exception) {
            return
        }
        if (result.risk >= SMS_RISK_THRESHOLD) {
            alert(
                id = sender.hashCode(),
                title = "⚠ Risky message from $sender — ${result.level.label} ${result.risk}",
                text = result.simple.ifEmpty { result.userMessage.ifEmpty { "Tap to see why this was flagged." } }
            )
        }
    }

    // ---- Notifications ----

    /**
     * Leave the foreground without taking the shared notification down.
     *
     * Only one notification id exists now, so a blind stopForeground(true) here
     * would delete the daemon's own notification on its way out. When the daemon
     * is alive the line stays with it; with no daemon (protection switched off
     * mid-job) there is nobody left to own it, so it is removed.
     */
    private fun releaseOngoing() {
        val daemonAlive = try {
            AlwaysOnService.isRunning(this)
        } catch (_: Exception) {
            false
        }
        try {
            if (daemonAlive) stopForeground(STOP_FOREGROUND_DETACH) else stopForeground(true)
        } catch (_: Exception) {
            try {
                stopForeground(true)
            } catch (_: Exception) {
            }
        }
    }

    private fun alert(id: Int, title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(
                id,
                NotificationCompat.Builder(this, CHANNEL_ALERT)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setAutoCancel(true)
                    .setContentIntent(mainIntent())
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    private fun mainIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
