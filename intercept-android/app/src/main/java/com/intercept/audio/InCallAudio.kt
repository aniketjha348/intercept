package com.intercept.audio

import android.content.Context
import android.media.AudioManager

/**
 * Screening audio routing: speakerphone on so the caller hears the guardian
 * voice and the mic picks the caller up. Previous audio state is restored
 * when screening ends. Needs MODIFY_AUDIO_SETTINGS (manifest).
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
            audio?.isSpeakerphoneOn = true
            // Caller must HEAR the guardian: max the voice-call stream
            // (uplink injection volume follows it on most devices).
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
