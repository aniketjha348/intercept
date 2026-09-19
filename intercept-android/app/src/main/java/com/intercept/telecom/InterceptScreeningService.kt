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
 * The routing decision for every incoming call, and the only place a call can
 * be sent to the AI.
 *
 * There is exactly one path that lets the caller hear Intercept: carrier
 * forwarding is armed, so the unknown call is DECLINED here, the network hands
 * it to our LiveKit number, and the cloud agent answers as the other party.
 *
 * Everything else rings normally. Nothing is ever answered by the app: on-device
 * answering could only play audio out of the owner's own earpiece (a store app
 * cannot write into a cellular uplink), which is what used to reach callers as a
 * screech. When forwarding is NOT armed but the owner asked for automatic
 * protection, we say so once instead of silently doing nothing.
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

        // Contact lookup is permission-backed; if it fails we must not guess.
        // "not unknown" keeps a contact ringing instead of forwarding it.
        val unknown = try {
            ContactHelper.isUnknown(applicationContext, number)
        } catch (_: Exception) {
            false
        }

        // Carrier forwarding armed: DECLINE the call so the network forwards it
        // to our number, where the AI answers as the other party. This is the
        // only path where the caller ever hears the AI, because no store app can
        // speak into a live cellular call.
        val forwarding = try {
            container.forwardingOn
        } catch (_: Exception) {
            false
        }
        if (forwarding && unknown) {
            val reject = CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
            respondToCall(callDetails, reject)
            showForwardedNotification(number)
            return
        }

        // Otherwise the call is allowed through and rings normally. (No
        // allow-flag exists: a response WITHOUT disallow/reject IS allow.)
        val response = CallResponse.Builder()
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)

        // Automatic protection was switched on, but without carrier forwarding
        // there is no way for a store app to speak to the caller. Say so once
        // rather than let the owner believe the AI is screening this call.
        val wantsAi = try {
            container.autoCalls
        } catch (_: Exception) {
            false
        }
        if (unknown && wantsAi) showForwardingNeededNotification(number)
    }

    /** The call is on its way to the cloud AI — tell the owner why it stopped ringing. */
    private fun showForwardedNotification(number: String) {
        val channelId = "intercept_forwarding"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "AI answering", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI is answering $number")
            .setContentText("Watch the call or join any time.")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(number.hashCode(), notification)
    }

    /** The AI could not take this call because forwarding is off — ask once. */
    private fun showForwardingNeededNotification(number: String) {
        val channelId = "intercept_screening"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Call screening", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Unknown caller: $number")
            .setContentText("Turn on AI answering so Intercept can take strangers for you.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Intercept can only speak to a caller when the call is handed to " +
                        "the cloud AI — turn on AI answering (carrier forwarding) from Home."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(number.hashCode(), notification)
    }
}
