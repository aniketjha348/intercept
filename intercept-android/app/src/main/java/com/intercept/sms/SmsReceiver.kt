package com.intercept.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.intercept.appContainer
import com.intercept.service.AutoScreenService
import com.intercept.telecom.ContactHelper

/**
 * Incoming-SMS tripwire: strangers' texts are scanned automatically, with
 * zero taps. Only unknown senders are analyzed; contacts ring through silent.
 * Needs RECEIVE_SMS (asked once during setup).
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val container = try {
            context.appContainer()
        } catch (_: Exception) {
            return
        }
        if (!container.autoSms) return
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: Exception) {
            return
        }
        if (messages.isEmpty()) return
        val sender = messages[0].originatingAddress ?: "Unknown"
        if (!ContactHelper.isUnknown(context, sender)) return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (body.isBlank()) return
        AutoScreenService.screenSms(context, sender, body)
    }
}
