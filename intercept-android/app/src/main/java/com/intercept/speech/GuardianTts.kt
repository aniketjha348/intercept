package com.intercept.speech

import android.content.Context
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

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }
}
