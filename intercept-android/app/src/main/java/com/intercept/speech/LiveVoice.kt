package com.intercept.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Realtime voice path (beta): mic PCM16/16kHz streams to the backend Live
 * bridge, guardian voice PCM16/24kHz streams back and plays into the call.
 * Barge-in is native (the model stops when the caller speaks). Any failure →
 * onError so the UI falls back to the STT+TTS turn path. Never throws.
 */
class LiveVoice(context: Context, backendUrl: String, sessionId: String) {

    private val appContext = context.applicationContext
    private val url = backendUrl.replace("http", "ws").trimEnd('/') + "/ws/live/$sessionId"

    var onTranscript: ((speaker: String, text: String) -> Unit)? = null
    var onRisk: ((score: Int, level: String) -> Unit)? = null
    var onTerminated: ((reason: String) -> Unit)? = null
    var onError: (() -> Unit)? = null

    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var scope: CoroutineScope? = null
    private var micJob: Job? = null

    @Volatile
    var running = false
        private set

    fun start(client: OkHttpClient): Boolean {
        if (running) return true
        try {
            val minIn = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minIn <= 0) {
                fail()
                return false
            }
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minIn * 4
                )
            } catch (_: Exception) {
                fail()
                return false
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                try {
                    rec.release()
                } catch (_: Exception) {
                }
                fail()
                return false
            }
            val minOut = AudioTrack.getMinBufferSize(
                24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minOut <= 0) {
                try {
                    rec.release()
                } catch (_: Exception) {
                }
                fail()
                return false
            }
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(24000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minOut * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                try {
                    rec.release()
                } catch (_: Exception) {
                }
                fail()
                return false
            }
            recorder = rec
            player = track
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            running = true
            try {
                rec.startRecording()
            } catch (_: Exception) {
                stop()
                fail()
                return false
            }
            try {
                track.play()
            } catch (_: Exception) {
                stop()
                fail()
                return false
            }
            ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
            micJob = scope?.launch { micLoop(rec) }
            return true
        } catch (_: Exception) {
            stop()
            fail()
            return false
        }
    }

    private fun micLoop(rec: AudioRecord) {
        // Gemini Live expects audio frames ~200ms at 16kHz = 3200 samples.
        // We read in 200ms chunks for proper synchronization with the backend.
        val frameSize = 3200 // 200ms @ 16kHz
        val buf = ShortArray(frameSize)

        while (running) {
            try {
                // Read blocks until frame size samples captured or timeout
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    // No data captured - continue reading
                    continue
                }

                // Convert PCM16 little-endian samples to bytes
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }

                // Encode to base64 and send via WebSocket
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ws?.send(JSONObject().put("type", "audio").put("data", b64).toString())

            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    fun say(text: String) {
        if (!running || text.isBlank()) return
        try {
            ws?.send(JSONObject().put("type", "say").put("text", text).toString())
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running = false
        try {
            micJob?.cancel()
        } catch (_: Exception) {
        }
        micJob = null
        try {
            ws?.send(JSONObject().put("type", "end").toString())
        } catch (_: Exception) {
        }
        try {
            ws?.close(1000, null)
        } catch (_: Exception) {
        }
        ws = null
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            scope?.cancel()
        } catch (_: Exception) {
        }
        scope = null
    }

    private fun fail() {
        try {
            onError?.invoke()
        } catch (_: Exception) {
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = JSONObject(text)
                when (o.optString("event")) {
                    "TRANSCRIPT_UPDATED" -> onTranscript?.invoke(
                        o.optString("speaker"), o.optString("text")
                    )
                    "RISK_UPDATED" -> onRisk?.invoke(o.optInt("risk"), o.optString("level"))
                    "CALL_TERMINATED" -> {
                        onTerminated?.invoke(o.optString("reason"))
                        stop()
                    }
                    "VOICE_AUDIO" -> {
                        val data = o.optString("data")
                        if (data.isNotEmpty()) playChunk(data)
                    }
                }
            } catch (_: Exception) {
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val was = running
            stop()
            if (was) fail()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code != 1000 && running) {
                stop()
                fail()
            }
        }
    }

    private fun playChunk(b64: String) {
        try {
            val track = player ?: return
            if (!running) return
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (bytes.isEmpty()) return
            track.write(bytes, 0, bytes.size)
        } catch (_: Exception) {
        }
    }

}
