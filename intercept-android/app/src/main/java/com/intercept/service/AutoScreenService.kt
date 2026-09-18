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
import com.intercept.speech.CallerStt
import com.intercept.telecom.InterceptInCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless auto-protect engine. After the one-time setup, everything here runs
 * with zero taps: stranger SMS are scanned silently (warning only when risky),
 * unknown calls are answered on speaker and screened by the AI until they end
 * or turn CRITICAL (auto-hangup + security report).
 */
class AutoScreenService : Service() {

    companion object {
        private const val ACTION_SMS = "com.intercept.action.SCREEN_SMS"
        private const val ACTION_CALL = "com.intercept.action.SCREEN_CALL"
        private const val CHANNEL_ALERT = "intercept_alert"
        private const val SMS_RISK_THRESHOLD = 25

        @Volatile var activeCallSession: String? = null
            private set
        @Volatile var activeCallNumber: String? = null
            private set

        fun screenSms(context: Context, sender: String, body: String) {
            val intent = Intent(context, AutoScreenService::class.java)
                .setAction(ACTION_SMS)
                .putExtra("sender", sender)
                .putExtra("body", body)
            run(intent, context)
        }

        fun screenCall(context: Context, number: String) {
            val intent = Intent(context, AutoScreenService::class.java)
                .setAction(ACTION_CALL)
                .putExtra("number", number)
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
    private var stt: CallerStt? = null
    private val callDone = AtomicBoolean(false)

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
                        if (activeCallSession == null) {
                            releaseOngoing()
                            stopSelf(startId)
                        }
                    }
                }
            }
            ACTION_CALL -> {
                val number = intent.getStringExtra("number").orEmpty()
                scope.launch { handleCall(number, startId) }
            }
            else -> if (activeCallSession == null) {
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

    // ---- Calls: answer already done by InCallService; run the AI loop ----

    private suspend fun handleCall(number: String, startId: Int) {
        if (activeCallSession != null) return // one screened call at a time
        callDone.set(false)
        val container = try {
            appContainer()
        } catch (_: Exception) {
            bailOut(startId)
            return
        }
        val sid = try {
            container.repo.startCall(number.ifEmpty { "Unknown" }, container.ownerName)
        } catch (_: Exception) {
            bailOut(startId)
            return
        }
        activeCallSession = sid
        activeCallNumber = number
        container.lastSessionId = sid
        container.sessionCallers[sid] = number
        AutoProtectNotification.update(this, "Screening call from $number…", sid)
        try {
            container.audio.enter()
        } catch (_: Exception) {
        }
        try {
            container.tts.setCallMode(true)
        } catch (_: Exception) {
        }
        try {
            container.speakBest(
                sid,
                "Namaste! Main INTERCEPT hoon, is call ki suraksha jaanch kar raha hoon. " +
                    "Kripya apna naam aur kaam batayein. " +
                    "Hello, this call is being screened. Please introduce yourself.",
                forCall = true
            )
        } catch (_: Exception) {
        }
        withContext(Dispatchers.Main) { startEars(sid) }
        // Wait until the caller hangs up or the AI terminates.
        while (!callDone.get() && InterceptInCallService.hasCall()) {
            delay(1500)
        }
        finishCall(sid, terminated = callDone.get())
        releaseOngoing()
        stopSelf(startId)
    }

    private fun startEars(sid: String) {
        val container = try {
            appContainer()
        } catch (_: Exception) {
            return
        }
        val ears = try {
            container.callerStt()
        } catch (_: Exception) {
            return
        }
        if (!ears.isAvailable()) return
        stt = ears
        ears.start(
            onPartialText = {},
            onFinalText = { text ->
                scope.launch {
                    try {
                        // Barge-in: caller spoke → cut our voice like an interrupted human.
                        container.stopVoice()
                        val turn = container.repo.sendCallerTurn(sid, text)
                        if (!turn.reply.isBlank()) {
                            container.speakBest(sid, turn.reply, forCall = true)
                        }
                        if (turn.mustTerminate && callDone.compareAndSet(false, true)) {
                            finishCall(sid, terminated = true)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        )
    }

    private suspend fun finishCall(sid: String, terminated: Boolean) {
        if (!callDone.compareAndSet(false, true) && !terminated) return
        val container = try {
            appContainer()
        } catch (_: Exception) {
            null
        }
        try {
            stt?.stop()
        } catch (_: Exception) {
        }
        stt = null
        if (container != null) {
            try {
                container.stopVoice()
            } catch (_: Exception) {
            }
        }
        try {
            InterceptInCallService.hangup()
        } catch (_: Exception) {
        }
        var level = ""
        var risk = 0
        if (container != null) {
            try {
                val report = container.repo.endCall(sid)
                level = report.level
                risk = report.risk
            } catch (_: Exception) {
            }
            try {
                container.audio.exit()
            } catch (_: Exception) {
            }
            try {
                container.tts.setCallMode(false)
            } catch (_: Exception) {
            }
        }
        activeCallSession = null
        activeCallNumber = null
        AutoProtectNotification.update(this, AutoProtectNotification.IDLE_TEXT)
        if (terminated || risk >= 50) {
            alert(
                id = sid.hashCode(),
                title = if (terminated) "🛡 Scam call stopped — $level $risk"
                else "Call screened — $level $risk",
                text = "Open INTERCEPT → Reports for the full security report."
            )
        }
    }

    // ---- Notifications ----

    /**
     * Nothing to screen after all: give the ongoing line back and stand down,
     * instead of holding the foreground (and its notification) forever.
     */
    private fun bailOut(startId: Int) {
        releaseOngoing()
        stopSelf(startId)
    }

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
        // Screening live right now? Tap jumps straight to the transcript so
        // the user can read along, take over, or cut the call themselves.
        activeCallSession?.let { intent.putExtra(MainActivity.EXTRA_WATCH_SID, it) }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        try {
            stt?.stop()
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }
}
