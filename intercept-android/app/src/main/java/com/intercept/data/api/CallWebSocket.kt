package com.intercept.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** §25 realtime stream. Server pushes every state change — no polling. */
sealed interface CallEvent {
    data class Started(val caller: String) : CallEvent
    data class Transcript(val speaker: String, val text: String) : CallEvent
    data class Signal(val code: String, val category: String, val confidence: Double, val evidence: String?) : CallEvent
    data class Risk(val score: Int, val level: String) : CallEvent
    data class AttackChain(val stages: List<String>) : CallEvent
    data class AiReply(val text: String) : CallEvent
    data class Takeover(val offer: Boolean, val humanMode: Boolean) : CallEvent
    data class Terminated(val reason: String) : CallEvent
}

class CallWebSocket(
    client: OkHttpClient,
    baseUrl: String,
    sessionId: String,
    private val onEvent: (CallEvent) -> Unit,
    private val onError: () -> Unit = {},
) {
    private val url = baseUrl.replace("http", "ws").trimEnd('/') + "/ws/calls/$sessionId"
    private var ws: WebSocket? = null
    private var dead = false

    private fun fail() {
        if (dead) return
        dead = true
        try {
            onError()
        } catch (_: Exception) {
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            parse(text)?.let(onEvent)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Abnormal close only; clean shutdowns call close() after the call ends.
            if (code != 1000) fail()
        }
    }

    init {
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun sendCallerTurn(text: String, language: String = "auto") {
        val msg = JSONObject().put("type", "caller_turn").put("text", text)
        if (language != "auto") msg.put("language", language)
        ws?.send(msg.toString())
    }

    fun sendTakeover() {
        ws?.send(JSONObject().put("type", "takeover").toString())
    }

    fun close() {
        try {
            ws?.send(JSONObject().put("type", "end").toString())
        } catch (_: Exception) {
        }
        ws?.close(1000, null)
        ws = null
    }

    private fun parse(text: String): CallEvent? = try {
        val o = JSONObject(text)
        when (o.optString("event")) {
            "CALL_STARTED" -> CallEvent.Started(o.optString("caller"))
            "TRANSCRIPT_UPDATED" -> CallEvent.Transcript(o.optString("speaker"), o.optString("text"))
            "SIGNAL_DETECTED" -> CallEvent.Signal(
                o.optString("code"), o.optString("category"),
                o.optDouble("confidence"), o.optString("evidence").ifEmpty { null })
            "RISK_UPDATED" -> CallEvent.Risk(o.optInt("risk"), o.optString("level"))
            "ATTACK_STAGE_CHANGED" -> {
                val stages = mutableListOf<String>()
                val arr = o.optJSONArray("chain")
                if (arr != null) for (i in 0 until arr.length()) {
                    stages += arr.optJSONObject(i)?.optString("stage").orEmpty()
                }
                CallEvent.AttackChain(stages)
            }
            "AI_RESPONSE_FINISHED" -> CallEvent.AiReply(o.optString("text"))
            "TAKEOVER_AVAILABLE" -> CallEvent.Takeover(o.optBoolean("offer"), o.optBoolean("human_mode"))
            "CALL_TERMINATED" -> CallEvent.Terminated(o.optString("reason"))
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
