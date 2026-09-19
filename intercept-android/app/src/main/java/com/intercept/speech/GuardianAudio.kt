package com.intercept.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream

/**
 * Human voice player: plays server-rendered guardian audio (Gemini TTS WAV) on
 * the MEDIA stream — the demo/analyze path, never a live call. stop() =
 * barge-in: when the caller starts talking we cut our own voice instantly,
 * exactly like a human interrupted mid-sentence. All failures are silent —
 * callers use device TTS instead (see GuardianTts).
 */
class GuardianAudio(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var player: MediaPlayer? = null

    /**
     * Play WAV bytes on the media stream. Deliberately never the voice-call
     * stream: that usage cannot reach a cellular uplink from a store app, it
     * only blasts out of the owner's own earpiece at full volume and leaks back
     * through the open mic — the screech callers used to hear.
     */
    fun play(wav: ByteArray) {
        stop()
        if (wav.isEmpty()) return
        try {
            val file = File(appContext.cacheDir, "guardian.wav")
            FileOutputStream(file).use { it.write(wav) }
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnCompletionListener {
                try {
                    it.reset()
                    it.release()
                } catch (_: Exception) {
                }
                if (player === it) player = null
            }
            mp.setOnErrorListener { it, _, _ ->
                try {
                    it.reset()
                    it.release()
                } catch (_: Exception) {
                }
                if (player === it) player = null
                true
            }
            player = mp
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        val mp = player
        player = null
        if (mp == null) return
        try {
            if (mp.isPlaying) mp.stop()
        } catch (_: Exception) {
        }
        try {
            mp.reset()
            mp.release()
        } catch (_: Exception) {
        }
    }
}
