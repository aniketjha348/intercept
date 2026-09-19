package com.intercept.telecom

import android.media.AudioManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Production real-call path: when INTERCEPT is the default Phone app, ringing
 * cellular calls land here — and that is all this service does.
 *
 * It deliberately never answers a call. A store app cannot put audio INTO a
 * cellular uplink, so the old "answer and speak the guardian voice" path could
 * only ever blast whatever it played out of the owner's own earpiece at max
 * volume while the open mic fed that same audio back up the line — the caller
 * heard a screech, not a voice. Calls that must reach the AI are DECLINED by
 * InterceptScreeningService and forwarded by the carrier to LiveKit, where the
 * cloud agent answers as the other party. This service only shows the in-call
 * UI so the owner can talk, hang up, or take over normally.
 */
class InterceptInCallService : InCallService() {

    private val calls = mutableSetOf<Call>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        calls.add(call)
        instance = this
        restoreVoiceStream()
        // We own the in-call UI (default Phone app), so every call — incoming,
        // outgoing, contact or stranger — gets on-screen controls.
        try {
            CallActiveActivity.show(this)
        } catch (_: Exception) {
        }
    }

    /**
     * Heal the one leftover of the removed on-device screening. It muted
     * STREAM_VOICE_CALL and left the stream at full volume; that mute is a
     * device-wide audio setting, so it survives the app being killed mid-call —
     * after which the owner hears NOTHING on every later call. Unmute here,
     * where we know a call is genuinely coming through.
     */
    private fun restoreVoiceStream() {
        try {
            val am = getSystemService(AudioManager::class.java) ?: return
            am.adjustStreamVolume(
                AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0
            )
        } catch (_: Exception) {
        }
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

        /** Answer on earpiece. Answering is always the owner's own tap. */
        fun answer(): Boolean {
            val svc = instance ?: return false
            val call = svc.target() ?: return false
            return try {
                if (call.state == Call.STATE_RINGING) call.answer(0)
                svc.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
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
