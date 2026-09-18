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
            // AI screening is silent to the owner: earpiece, never speaker.
            audio?.isSpeakerphoneOn = false
            // Caller must HEAR the guardian: max the voice-call stream
            // (this is the uplink, the direction the owner is not listening to).
            prevVolume = audio?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: -1
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: -1
            if (max > 0) {
                audio?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
            }
        } catch (_: Exception) {
        }
    }

    fun exit() {
        if (!active) return
        active = false
        try {
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
