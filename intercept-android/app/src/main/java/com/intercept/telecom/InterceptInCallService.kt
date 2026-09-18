package com.intercept.telecom

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.intercept.appContainer
import com.intercept.service.AutoScreenService

/**
 * Production real-call path: when INTERCEPT is the default Phone app,
 * ringing cellular calls land here. answer() picks up on speakerphone so the
 * guardian voice (TTS) reaches the caller and the mic/STT hears them back.
 * Needs the default-dialer role; audio couples through the speaker (honest v1 —
 * Pixel-style deep in-call audio needs system privileges no store app gets).
 */
class InterceptInCallService : InCallService() {

    private val calls = mutableSetOf<Call>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        calls.add(call)
        instance = this
        // Auto-screened calls run headless; every other call (outgoing, saved
        // contacts, toggles off) gets on-screen controls — as the default Phone
        // app we own the in-call UI, so we must always show something.
        if (!maybeAutoAnswer(call)) {
            try {
                CallActiveActivity.show(this)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Zero-tap path: after the one-time setup, unknown callers are answered
     * automatically and handed to the headless AI screener. Saved contacts
     * and disabled toggles always ring through normally. Returns true when
     * the auto path was taken.
     */
    private fun maybeAutoAnswer(call: Call): Boolean {
        val number = try {
            call.details?.handle?.schemeSpecificPart ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
        val auto = try {
            val container = applicationContext.appContainer()
            container.setupDone && container.autoCalls &&
                ContactHelper.isUnknown(this, number)
        } catch (_: Exception) {
            false
        }
        if (!auto) return false
        // Answer immediately - no delay! The previous 1.5s delay caused race conditions
        // where the system would timeout or user would interact before answer completed.
        try {
            if (call.state == Call.STATE_RINGING && calls.contains(call)) {
                call.answer(0)
                setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                try {
                    applicationContext.appContainer().pendingIncomingCaller = number
                } catch (_: Exception) {
                }
                AutoScreenService.screenCall(applicationContext, number)
            }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        calls.remove(call)
        if (calls.isEmpty() && instance === this) instance = null
    }

    private fun target(): Call? = calls.firstOrNull {
        it.state == Call.STATE_RINGING || it.state == Call.STATE_ACTIVE
    } ?: calls.firstOrNull()

    companion object {
        @Volatile private var instance: InterceptInCallService? = null

        fun hasCall(): Boolean = instance?.target() != null

        fun isRinging(): Boolean = instance?.target()?.state == Call.STATE_RINGING

        /** Number + state for the on-screen call UI (null/-1 when no call). */
        fun currentNumber(): String? = try {
            instance?.target()?.details?.handle?.schemeSpecificPart
        } catch (_: Exception) {
            null
        }

        fun currentState(): Int = try {
            instance?.target()?.state ?: -1
        } catch (_: Exception) {
            -1
        }

        fun speakerOn(): Boolean = try {
            instance?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
        } catch (_: Exception) {
            false
        }

        /** Answer on speakerphone. False when no telecom call is held. */
        fun answer(): Boolean {
            val svc = instance ?: return false
            val call = svc.target() ?: return false
            return try {
                if (call.state == Call.STATE_RINGING) call.answer(0)
                svc.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun hangup(): Boolean {
            val svc = instance ?: return false
            val call = svc.target() ?: return false
            return try {
                call.disconnect()
                true
            } catch (_: Exception) {
                false
            }
        }

        fun setSpeaker(on: Boolean) {
            try {
                instance?.setAudioRoute(
                    if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
                )
            } catch (_: Exception) {
            }
        }
    }
}
