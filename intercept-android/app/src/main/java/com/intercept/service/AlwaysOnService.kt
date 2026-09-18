package com.intercept.service

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.intercept.appContainer
import com.intercept.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The always-on half of auto-protect.
 *
 * Detection itself is OS-bound (NotificationListenerService, SMS receiver,
 * InCallService) and needs no help from us — but those callbacks land in a
 * process the OS may reclaim the moment the user swipes the app off Recents.
 * This is what keeps that process alive: a foreground service with
 * stopWithTask=false, restarted by the OS (START_STICKY) and by the boot
 * receiver. Protection stops being something the user has to remember to keep
 * open. Its notification is the shared one (AutoProtectNotification), so a
 * screened call rewrites that same line instead of posting a second entry.
 *
 * Foreground start is deliberate about WHERE it is called from: starting a
 * foreground service from the background is banned on Android 12+, and the
 * notification listener can wake this process while the app is backgrounded —
 * so sync() is called from foreground entry points and from BOOT_COMPLETED /
 * MY_PACKAGE_REPLACED, which are documented exemptions. Never from
 * Application.onCreate.
 *
 * Honest limit: no Android app can promise immortality. OEM battery savers
 * still kill sticky services — that is what the battery-unrestricted gate in
 * Setup exists for.
 */
class AlwaysOnService : Service() {

    companion object {
        private const val HEARTBEAT_MS = 60_000L

        /** True when at least one auto-protection surface is switched on. */
        fun wanted(context: Context): Boolean = try {
            val c = context.applicationContext.appContainer()
            c.autoCalls || c.autoSms || c.autoApps
        } catch (_: Exception) {
            false
        }

        /** Match the running service to the user's switches. Safe to over-call. */
        fun sync(context: Context) {
            if (wanted(context)) start(context) else stop(context)
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, AlwaysOnService::class.java)
                )
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AlwaysOnService::class.java))
            } catch (_: Exception) {
            }
        }

        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
            @Suppress("DEPRECATION")
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == AlwaysOnService::class.java.name }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var beating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AutoProtectNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                AutoProtectNotification.ID,
                AutoProtectNotification.build(this, AutoProtectNotification.IDLE_TEXT),
            )
        } catch (_: Exception) {
        }
        // Nothing left to protect with: hold a notification for no reason.
        if (!wanted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!beating) {
            beating = true
            scope.launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    try {
                        healOverlay()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        // Swiped off Recents, low-memory kill: the OS brings us back.
        return START_STICKY
    }

    /**
     * The bubble is a window owned by a process, so it dies with us while the
     * user's toggle still says "on". Put it back instead of letting the
     * setting quietly become a lie.
     */
    private fun healOverlay() {
        val c = try {
            appContainer()
        } catch (_: Exception) {
            return
        }
        if (!c.overlayOn) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        if (OverlayService.isRunning(this)) return
        OverlayService.start(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
