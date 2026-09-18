package com.intercept.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.intercept.appContainer
import com.intercept.overlay.OverlayService
import com.intercept.service.AlwaysOnService

/**
 * A protection app that only works until the next reboot is not protection.
 *
 * Re-arms the always-on service and the floating bubble after a restart or an
 * in-app update, but only when the user actually left those switches on —
 * nothing is resurrected behind their back.
 *
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED are both documented exemptions from
 * the Android 12+ background foreground-service start ban, which is why the
 * restart happens here rather than from Application.onCreate.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        if (!relevant) return

        val app = context.applicationContext
        try {
            AlwaysOnService.sync(app)
        } catch (_: Exception) {
        }
        try {
            if (appContainerOrNull(app)?.overlayOn == true) {
                OverlayService.start(app)
            }
        } catch (_: Exception) {
        }
    }

    private fun appContainerOrNull(context: Context) = try {
        context.appContainer()
    } catch (_: Exception) {
        null
    }
}
