package com.intercept.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import com.intercept.MainActivity
import com.intercept.appContainer

/**
 * Channel Gateway on-device (§2): unknown callers ring through with a one-tap
 * "let the AI screen this" prompt when auto-answer is off, and are handed to
 * InterceptInCallService when it is on.
 *
 * The call is never disallowed on purpose: the tap-to-screen flow needs the
 * call still alive for the user to answer. Full auto-answer requires BOTH the
 * ROLE_CALL_SCREENING and the ROLE_DIALER role.
 */
class InterceptScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: "Unknown"
        val container = try {
            applicationContext.appContainer()
        } catch (_: Exception) {
            // If we can't access container, allow the call through
            val response = CallResponse.Builder()
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
            respondToCall(callDetails, response)
            return
        }

        // Auto-answer is only real when we hold the dialer role. Without it
        // InterceptInCallService never fires, so claiming the auto path left the
        // call ringing with no auto-answer AND no tap-to-screen prompt — the one
        // outcome where protection silently does nothing.
        val isDialer = try {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName
        } catch (_: Exception) {
            false
        }
        val shouldAutoAnswer = try {
            container.setupDone && container.autoCalls && isDialer &&
                ContactHelper.isUnknown(applicationContext, number)
        } catch (_: Exception) {
            false
        }

        // Either way the call is allowed through. (No allow-flag exists: a
        // response WITHOUT disallow/reject IS allow.)
        val response = CallResponse.Builder()
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)

        if (shouldAutoAnswer) {
            // InterceptInCallService detects this call and answers on speaker.
            return
        }

        container.pendingIncomingCaller = number
        showScreeningNotification(number)
    }

    private fun showScreeningNotification(number: String) {
        val channelId = "intercept_screening"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Call screening", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_INCOMING, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Unknown caller silenced: $number")
            .setContentText("Tap to let INTERCEPT screen this call.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(number.hashCode(), notification)
    }
}
