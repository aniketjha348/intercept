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
 * Studio-grade voice transport over LiveKit (WebRTC): the agent's audio plays
 * back the moment we are in the room, and this phone's mic is published only
 * when we ask for it. Transcript/risk keep flowing through OUR backend session
 * (the agent posts turns there) — so reports, policies and the whole risk engine
 * behave exactly as before.
 *
 * [publishMic] exists for the watch case. A forwarded call already has the
 * caller and the agent in that room; a watcher who also published a mic would
 * push a second voice into the conversation the agent is listening to, and the
 * caller would hear the owner's room as well as their own call.
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

    fun connect(url: String, token: String, publishMic: Boolean = false) {
        if (room != null) return
        scope.launch {
            try {
                try {
                    LiveKit.init(appContext)
                } catch (_: Exception) {
                }
                val r = LiveKit.create(appContext)
                r.connect(url, token)
                room = r
                if (publishMic) setMic(true)
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

    /** Publish or mute this phone's mic in the room. */
    fun setMic(on: Boolean) {
        val r = room ?: return
        scope.launch {
            try {
                r.localParticipant.setMicrophoneEnabled(on)
            } catch (e: Exception) {
                try {
                    onError?.invoke(e.message ?: "mic failed")
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
