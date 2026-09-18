package com.intercept.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Guardian voice (Call Guardian §12: AI RESPONSE → TTS → caller). */
class GuardianTts(context: Context) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts?.language = Locale("en", "IN")
                } catch (_: Exception) {
                }
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        // Match voice to reply script: Devanagari → Hindi voice, else Indian English.
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        tts?.language = if (hasDevanagari) Locale("hi", "IN") else Locale("en", "IN")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "intercept-guardian")
    }

    /**
     * Route replies for live call screening (voice-communication stream so the
     * caller hears them over the call). Call setCallMode(false) when done.
     */
    fun setCallMode(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (on) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    fun speakForCall(text: String) {
        setCallMode(true)
        speak(text)
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }
}
