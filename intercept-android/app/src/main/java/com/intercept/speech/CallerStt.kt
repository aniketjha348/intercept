package com.intercept.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Caller ears: continuous speech recognition for live screening.
 * Language follows the app setting — hi → hi-IN, hinglish/en → en-IN,
 * auto → device default (covers Hindi + Hinglish on Indian phones).
 * Needs RECORD_AUDIO (asked at runtime before starting).
 */
class CallerStt(context: Context, private val appLang: () -> String = { "auto" }) {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var onFinal: ((String) -> Unit)? = null
    private var onPartial: ((String) -> Unit)? = null
    private var onStopped: (() -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private var readyFired = false
    private var errors = 0 // consecutive failures → back off instead of hot-looping

    fun isAvailable(): Boolean =
        try {
            SpeechRecognizer.isRecognitionAvailable(appContext)
        } catch (_: Exception) {
            false
        }

    private fun localeTag(): String = when (appLang()) {
        "hi" -> "hi-IN"
        "hinglish", "en" -> "en-IN"
        else -> try {
            Locale.getDefault().toLanguageTag()
        } catch (_: Exception) {
            "en-IN"
        }
    }

    fun start(
        onPartialText: (String) -> Unit = {},
        onFinalText: (String) -> Unit,
        onStopped: () -> Unit = {},
        onReady: () -> Unit = {},
    ) {
        if (listening) return
        if (!isAvailable()) return
        onFinal = onFinalText
        onPartial = onPartialText
        this.onStopped = onStopped
        this.onReady = onReady
        readyFired = false
        errors = 0
        listening = true
        begin()
    }

    private fun begin() {
        if (!listening) return
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        // The call's audio mode is owned by InCallAudio for the whole call
        // (it enters before STT starts and exits after), so the recognizer
        // must not touch it here — flipping it back on stop() would drop
        // mid-screening routing.
        val active = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (_: Exception) {
            null
        } ?: return
        recognizer = active
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        active.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    try {
                        onPartial?.invoke(text)
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onResults(results: Bundle) {
                errors = 0
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    try {
                        onFinal?.invoke(text.trim())
                    } catch (_: Exception) {
                    }
                }
                restart()
            }

            override fun onError(error: Int) {
                errors++
                // No network / no speech continuously: back off (max 5s) so a long
                // screening session doesn't spin the mic and drain the battery.
                if (errors > 25) {
                    stop()
                    try {
                        onStopped?.invoke()
                    } catch (_: Exception) {
                    }
                    return
                }
                try {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { restart() }, (250L * errors).coerceAtMost(5000L)
                    )
                } catch (_: Exception) {
                    restart()
                }
            }

            override fun onReadyForSpeech(params: Bundle) {
                // The mic pipeline is genuinely alive (not just "started").
                if (!readyFired) {
                    readyFired = true
                    try {
                        onReady?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle) = Unit
        })
        try {
            active.startListening(intent)
        } catch (_: Exception) {
            restart()
        }
    }

    private fun restart() {
        if (!listening) return
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        begin()
    }

    fun stop() {
        listening = false
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        onFinal = null
        onPartial = null
        // Also drop these: they close over the ViewModel, and a stopped
        // recognizer held for the rest of the session kept it reachable.
        onStopped = null
        onReady = null
    }
}
