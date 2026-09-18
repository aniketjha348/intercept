package com.intercept.speech

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Studio-grade voice transport over LiveKit (WebRTC): mic publishes as Opus
 * (packet-loss proof, echo-managed), agent audio plays back automatically.
 * Transcript/risk keep flowing through OUR backend session (the room carries
 * the call session id, the agent posts turns there) — so reports, policies
 * and the whole risk engine behave exactly as before.
 */
class LiveKitCall(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var room: Room? = null
        private set

    var onConnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun connect(url: String, token: String) {
        if (room != null) return
        scope.launch {
            try {
                try {
                    LiveKit.init(appContext)
                } catch (_: Exception) {
                }
                val r = LiveKit.create(appContext)
                r.connect(url, token)
                try {
                    r.localParticipant.setMicrophoneEnabled(true)
                } catch (_: Exception) {
                }
                room = r
                try {
                    onConnected?.invoke()
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                room = null
                try {
                    onError?.invoke(e.message ?: "connect failed")
                } catch (_: Exception) {
                }
            }
        }
    }

    fun disconnect() {
        val r = room
        room = null
        try {
            scope.cancel()
        } catch (_: Exception) {
        }
        try {
            r?.disconnect()
        } catch (_: Exception) {
        }
        try {
            onDisconnected?.invoke()
        } catch (_: Exception) {
        }
    }
}
