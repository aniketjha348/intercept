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
    private var active = false

    fun enter() {
        if (active) return
        active = true
        try {
            prevMode = audio?.mode ?: AudioManager.MODE_NORMAL
            prevSpeaker = audio?.isSpeakerphoneOn == true
            audio?.mode = AudioManager.MODE_IN_COMMUNICATION
            audio?.isSpeakerphoneOn = true
        } catch (_: Exception) {
        }
    }

    fun exit() {
        if (!active) return
        active = false
        try {
            audio?.isSpeakerphoneOn = prevSpeaker
            audio?.mode = prevMode
        } catch (_: Exception) {
        }
    }
}
