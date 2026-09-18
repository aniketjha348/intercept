package com.intercept.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Human voice player: plays server-rendered guardian audio (Gemini TTS WAV)
 * into the call stream. stop() = barge-in: when the caller starts talking we
 * cut our own voice instantly, exactly like a human interrupted mid-sentence.
 * All failures are silent — callers use device TTS instead (see GuardianTts).
 */
class GuardianAudio(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var player: MediaPlayer? = null

    /** Play WAV bytes. forCall routes into the voice stream so the caller hears it. */
    fun play(wav: ByteArray, forCall: Boolean) {
        stop()
        if (wav.isEmpty()) return
        try {
            val file = File(appContext.cacheDir, "guardian.wav")
            FileOutputStream(file).use { it.write(wav) }
            val mp = MediaPlayer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (forCall) AudioAttributes.USAGE_VOICE_COMMUNICATION
                            else AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                mp.setAudioStreamType(
                    if (forCall) android.media.AudioManager.STREAM_VOICE_CALL
                    else android.media.AudioManager.STREAM_MUSIC
                )
            }
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
