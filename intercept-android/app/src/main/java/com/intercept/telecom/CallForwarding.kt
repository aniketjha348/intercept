package com.intercept.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Carrier call forwarding — the only way the AI can talk *to* the caller.
 *
 * A normal app cannot inject audio into a live cellular call (the call's audio
 * path is closed to store apps), so this uses the same activation as Equal AI:
 * the app declines the unknown call, the carrier forwards it to our number, and
 * the voice agent answers there as the other party. Arming forwarding is what
 * makes the AI the callee instead of a voice the owner hears on their own phone.
 *
 * The codes are standard GSM/MSC ones (Airtel/Jio/Vi). `#` must be
 * percent-encoded to survive the tel: URI.
 */
object CallForwarding {

    /** Forward when busy. A declined call reads as busy — this is our path. */
    fun activateWhenBusy(ctx: Context, number: String): Boolean = dial(ctx, "*67*$number#")

    fun disableWhenBusy(ctx: Context): Boolean = dial(ctx, "##67#")

    /** Forward when not answered — fallback where a decline is not delivered. */
    fun activateWhenUnanswered(ctx: Context, number: String): Boolean = dial(ctx, "*61*$number#")

    fun disableWhenUnanswered(ctx: Context): Boolean = dial(ctx, "##61#")

    /**
     * Dial a USSD control code as a call. The carrier answers in the dialer;
     * reading that reply needs a privileged connection service we do not have,
     * so `true` means "dispatched", not "carrier confirmed".
     */
    private fun dial(ctx: Context, code: String): Boolean = try {
        val uri = Uri.parse("tel:" + Uri.encode(code))
        ctx.startActivity(
            Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}
