package com.intercept.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat
import com.intercept.MainActivity
import com.intercept.appContainer

/**
 * Channel Gateway on-device (§2): unknown callers are silenced here when
 * auto-answer is disabled, and the user gets one tap to let the AI answer.
 * When auto-answer is enabled + default dialer role held, calls are allowed
 * through for InterceptInCallService to handle immediately.
 * Full auto-answer requires both ROLE_CALL_SCREENING and ROLE_DIALER.
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

        // Check if auto-answer is enabled and caller is unknown
        val shouldAutoAnswer = try {
            container.setupDone && container.autoCalls &&
                ContactHelper.isUnknown(applicationContext, number)
        } catch (_: Exception) {
            false
        }

        if (shouldAutoAnswer) {
            // Allow the call and let InterceptInCallService handle auto-answer
            // This is the headless auto-answer path
            val response = CallResponse.Builder()
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .setAllowCall(true)  // Explicitly allow the call through
                .build()
            respondToCall(callDetails, response)
            // InterceptInCallService will detect this call and handle auto-answer
            return
        }

        // Non-auto path: silence unknown callers, show notification for user to tap
        val response = CallResponse.Builder()
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)

        applicationContext.appContainer().pendingIncomingCaller = number
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
