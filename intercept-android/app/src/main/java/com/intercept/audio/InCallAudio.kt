package com.intercept.audio

import android.content.Context
import android.media.AudioManager

/**
 * Screening audio routing. The AI deals with the caller on the owner's behalf,
 * so the local ear stays on the EARPIECE: the owner holding the phone must not
 * hear the guardian voice. Only the caller does, through uplink injection —
 * STREAM_VOICE_CALL carries the TTS up the line regardless of local routing.
 * Speaker is reserved for a user-initiated takeover. Previous audio state is
 * restored when screening ends. Needs MODIFY_AUDIO_SETTINGS (manifest).
 */
class InCallAudio(context: Context) {

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    private var prevVolume = -1
    private var active = false

    fun enter() {
        if (active) return
        active = true
        try {
            prevMode = audio?.mode ?: AudioManager.MODE_NORMAL
            prevSpeaker = audio?.isSpeakerphoneOn == true
            audio?.mode = AudioManager.MODE_IN_COMMUNICATION
            // AI screening is SILENT to the owner: earpiece off, speaker off.
            // The owner must NOT hear the guardian voice or the caller at all —
            // the caller hears the guardian through the call uplink.
            audio?.isSpeakerphoneOn = false
            // Volume FIRST, mute LAST. setStreamVolume() takes a stream OUT of
            // mute, so muting before it silently undid the mute and the owner
            // heard the whole call. This order is the fix — do not swap it.
            prevVolume = audio?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: -1
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: -1
            if (max > 0) {
                audio?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
            }
            try {
                audio?.adjustStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.ADJUST_MUTE, 0
                )
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    fun exit() {
        if (!active) return
        active = false
        try {
            // Unmute so the user can hear again after takeover / call end.
            try {
                audio?.adjustStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.ADJUST_UNMUTE, 0
                )
            } catch (_: Exception) {
            }
            audio?.isSpeakerphoneOn = prevSpeaker
            audio?.mode = prevMode
            if (prevVolume >= 0) {
                audio?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVolume, 0)
            }
        } catch (_: Exception) {
        } finally {
            prevVolume = -1
        }
    }
}
