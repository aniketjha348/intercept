package com.intercept.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.intercept.data.DemoScript
import com.intercept.data.api.CallEvent
import com.intercept.data.api.CallWebSocket
import com.intercept.di.AppContainer
import com.intercept.speech.CallerStt
import com.intercept.telecom.InterceptInCallService
import com.intercept.domain.model.ChatLine
import com.intercept.domain.model.RiskLevel
import com.intercept.domain.model.Sig
import com.intercept.domain.model.Stage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveUiState(
    val transcript: List<ChatLine> = emptyList(),
    val risk: Int = 0,
    val level: RiskLevel = RiskLevel.LOW,
    val signals: List<Sig> = emptyList(),
    val chain: List<Stage> = emptyList(),
    val why: List<String> = emptyList(),
    val objective: String = "",
    val claimed: String = "",
    val watchOnly: Boolean = false,
    val similar: String? = null,
    val simple: String = "",
    val guardianText: String = "",
    val offerTakeover: Boolean = false,
    val mustTerminate: Boolean = false,
    val humanMode: Boolean = false,
    val realCall: Boolean = false,
    val listening: Boolean = false,
    val lkTransport: Boolean = false,
    val interim: String = "",
    val ended: Boolean = false,
    val endedReason: String = "",
    val connected: Boolean = false,
    val error: String? = null,
)

class LiveCallViewModel(
    val sessionId: String,
    val caller: String,
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var socket: CallWebSocket? = null
    private var useSocket = true
    private var demoJob: Job? = null
    private var stt: CallerStt? = null
    private var lkCall: com.intercept.speech.LiveKitCall? = null
    /**
     * Remote watch (forwarded call): our backend session, agent's voice. While
     * true every path that would speak, answer, or end the session stays shut —
     * a watcher can never talk over the agent, and a glance can never hang up
     * someone else's call. joinCall() lifts it.
     */
    private var watching: Boolean = container.watchOnlySid == sessionId

    /**
     * The room this call is happening in. A forwarded call lives in the SIP
     * dispatch rule's room (named after the caller) — NOT in `intercept-<id>` —
     * so the room is resolved once here and reused for watch and for join.
     * Asking for the wrong room joins an empty room silently.
     */
    private val callRoom: String =
        container.watchOnlyRoom?.takeIf { it.isNotBlank() } ?: "intercept-$sessionId"

    init {
        if (watching) {
            _state.update { it.copy(watchOnly = true) }
        }
    }
    fun connect() {
        try {
            socket = CallWebSocket(container.http, container.backendUrl, sessionId, ::onEvent) {
                // Stream died mid-call (tunnel drop, network hop): fall back to REST
                // so typed/mic turns keep working instead of vanishing silently.
                useSocket = false
                _state.update {
                    it.copy(
                        connected = false,
                        error = "Live stream dropped — backup channel on. Resend if a reply stalls."
                    )
                }
            }
            _state.update { it.copy(connected = true) }
        } catch (_: Exception) {
            useSocket = false
            _state.update { it.copy(connected = false) }
        }
    }

    private fun onEvent(e: CallEvent) {
        when (e) {
            is CallEvent.Transcript ->
                _state.update { it.copy(transcript = it.transcript + ChatLine(e.speaker, e.text)) }
            is CallEvent.Signal -> _state.update { s ->
                val next = (s.signals.filter { it.code != e.code } +
                    Sig(e.code, e.category, e.confidence, e.evidence))
                s.copy(signals = next)
            }
            is CallEvent.Risk ->
                _state.update { it.copy(risk = e.score, level = RiskLevel.of(e.level)) }
            is CallEvent.AttackChain -> _state.update { s ->
                val stages = (s.chain.map { it.stage } + e.stages).distinct()
                s.copy(chain = stages.map { Stage(it, 0.85) })
            }
            is CallEvent.AiReply -> {
                _state.update { it.copy(guardianText = e.text) }
                if (!watching) {
                    viewModelScope.launch {
                        container.speakBest(sessionId, e.text, _state.value.realCall)
                    }
                }
            }
            is CallEvent.Takeover ->
                _state.update { it.copy(offerTakeover = e.offer, humanMode = e.humanMode) }
            is CallEvent.Terminated -> {
                _state.update { it.copy(ended = true, endedReason = e.reason) }
                stopRealCallAudio()
            }
            is CallEvent.Started -> Unit
        }
    }

    fun sendCallerText(text: String) {
        if (text.isBlank() || _state.value.ended) return
        // Watcher: the agent owns this conversation — never inject a turn.
        if (watching) return
        // Barge-in: caller started talking → cut our voice instantly.
        try {
            container.stopVoice()
        } catch (_: Exception) {
        }
        if (useSocket && socket != null) {
            socket?.sendCallerTurn(text, container.language)
        } else {
            restTurn(text)
        }
    }

    private fun restTurn(text: String) = viewModelScope.launch {
        _state.update { it.copy(transcript = it.transcript + ChatLine("caller", text)) }
        try {
            val r = container.repo.sendCallerTurn(sessionId, text)
            _state.update {
                it.copy(
                    transcript = it.transcript + ChatLine("intercept", r.reply),
                    risk = r.risk, level = r.level, signals = r.signals,
                    chain = r.chain, why = _explainFallback(r.why),
                    objective = r.objective, claimed = r.claimedOrg, similar = r.similar, simple = r.simple,
                    guardianText = r.reply, offerTakeover = r.offerTakeover,
                    mustTerminate = r.mustTerminate, error = null,
                    ended = r.mustTerminate,
                    endedReason = if (r.mustTerminate) r.simple else it.endedReason,
                )
            }
            if (!watching) {
                container.speakBest(sessionId, r.reply, _state.value.realCall)
            }
            if (r.mustTerminate) stopRealCallAudio()
        } catch (e: Exception) {
            _state.update { it.copy(error = "Backend error: ${e.message}") }
        }
    }

    private fun _explainFallback(why: List<String>) = why

    /** Streams the scripted scam so judges watch risk climb live. */
    fun playDemo() {
        if (demoJob?.isActive == true) return
        demoJob = viewModelScope.launch {
            for (line in DemoScript.lines) {
                if (_state.value.ended) break
                sendCallerText(line)
                delay(2200)
            }
        }
    }

    /**
     * Production path: a real telecom call is up. It is read-only — nothing
     * here plays audio into the call, because a store app cannot reach a
     * cellular uplink (see InterceptInCallService). Transcript, risk and the
     * take-over controls are the whole feature; the caller only hears the AI
     * when the call was forwarded to LiveKit. STT starts separately via
     * startListening() (needs RECORD_AUDIO granted — the screen asks for it).
     */
    fun beginRealScreening() {
        try {
            InterceptInCallService.setSpeaker(true)
        } catch (_: Exception) {
        }
        _state.update { it.copy(realCall = true) }
    }

    private var micReady = false
    private var micWatch: Job? = null

    /** Caller ears on: every recognized sentence streams to the backend. */
    fun startListening() {
        if (_state.value.listening || _state.value.ended) return
        // Our mic would fight the agent's audio for the same call.
        if (watching) return
        val active = try {
            container.callerStt()
        } catch (_: Exception) {
            null
        } ?: return
        if (!active.isAvailable()) {
            _state.update { it.copy(error = "Speech recognition not available on this device — type instead.") }
            return
        }
        stt = active
        micReady = false
        _state.update { it.copy(listening = true, error = null) }
        active.start(
            onPartialText = { part -> _state.update { it.copy(interim = part) } },
            onFinalText = { text ->
                _state.update { it.copy(interim = "") }
                sendCallerText(text)
            },
            onStopped = {
                _state.update {
                    it.copy(listening = false, error = "Mic stopped (no speech service) — type instead or retry.")
                }
            },
            // Proven live only: the mic icon turns on the moment the speech
            // service answers, never on blind hope. Silence breeds distrust.
            onReady = {
                micReady = true
                try {
                    micWatch?.cancel()
                } catch (_: Exception) {
                }
                _state.update { it.copy(error = null) }
            }
        )
        try {
            micWatch?.cancel()
        } catch (_: Exception) {
        }
        micWatch = viewModelScope.launch {
            kotlinx.coroutines.delay(8000)
            if (!micReady && _state.value.listening && stt != null) {
                stopListening()
                _state.update {
                    it.copy(error = "Mic not responding — check connection, then tap mic to retry.")
                }
            }
        }
    }

    fun stopListening() {
        try {
            micWatch?.cancel()
        } catch (_: Exception) {
        }
        micWatch = null
        try {
            stt?.stop()
        } catch (_: Exception) {
        }
        stt = null
        if (_state.value.listening || _state.value.interim.isNotEmpty()) {
            _state.update { it.copy(listening = false, interim = "") }
        }
    }

    /**
     * Live transport: join the call's LiveKit room — the same room the SIP leg
     * landed the agent in — and either just hear it (a watch) or publish this
     * phone's mic to take the conversation over. Transcript and risk keep
     * arriving through the backend session the agent posts turns into.
     *
     * If the room cannot be reached we fall back to on-device speech recognition
     * so the screen still works; we never claim a mic is live when it is not.
     */
    fun startLiveKitTransport(publishMic: Boolean = true) {
        if (_state.value.lkTransport || _state.value.ended) return
        // A watcher listens. It never opens the mic into someone else's call.
        if (watching && publishMic) return
        stopListening()
        viewModelScope.launch {
            val tok = try {
                container.repo.livekitToken(sessionId, callRoom)
            } catch (_: Exception) {
                null
            }
            val call = if (tok == null) null else try {
                com.intercept.speech.LiveKitCall(container.appContextForVoice())
            } catch (_: Exception) {
                null
            }
            if (tok == null || call == null) {
                fallbackToLocalMic(publishMic)
                return@launch
            }
            call.onConnected = {
                _state.update {
                    it.copy(lkTransport = true, listening = publishMic, error = null)
                }
                // Only OUR OWN rooms need the agent summoned. A SIP room already
                // has it (the dispatch rule put it there) and dispatching again
                // would put a second agent in the room, talking over the first —
                // which the caller hears as two overlapping voices.
                if (publishMic && callRoom == "intercept-$sessionId") {
                    viewModelScope.launch {
                        try {
                            container.repo.livekitDispatch(callRoom)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            call.onError = { msg ->
                lkCall = null
                _state.update {
                    it.copy(lkTransport = false, listening = false,
                        error = "Live transport dropped ($msg) — mic/typed turns still work.")
                }
            }
            call.onDisconnected = {
                lkCall = null
                if (_state.value.lkTransport) {
                    _state.update { it.copy(lkTransport = false, listening = false) }
                }
            }
            lkCall = call
            try {
                call.connect(tok.url, tok.token, publishMic)
            } catch (_: Exception) {
                lkCall = null
                fallbackToLocalMic(publishMic)
            }
        }
    }

    /**
     * The room is out of reach (no LiveKit on the backend, call already ended):
     * keep the screen useful with on-device speech recognition instead of an
     * error the owner cannot act on.
     */
    private fun fallbackToLocalMic(publishMic: Boolean) {
        _state.update { it.copy(lkTransport = false, listening = false) }
        if (!publishMic) {
            _state.update { it.copy(error = "Could not join the call room — transcript only.") }
            return
        }
        startListening()
    }

    /** Publish or mute this phone's mic in the call's room. */
    fun setMicEnabled(on: Boolean) {
        if (watching && on) return
        try {
            lkCall?.setMic(on)
        } catch (_: Exception) {
        }
        _state.update { it.copy(listening = on) }
    }

    /** Watch a forwarded call: in the room, muted, hearing the agent. */
    fun startWatching() = startLiveKitTransport(publishMic = false)

    fun stopLiveKitTransport() {
        try {
            lkCall?.disconnect()
        } catch (_: Exception) {
        }
        lkCall = null
        if (_state.value.lkTransport || _state.value.listening) {
            _state.update { it.copy(lkTransport = false, listening = false) }
        }
    }


    /** Hang up the telecom call (backend session ends separately). */
    private fun stopRealCallAudio() {
        try {
            container.stopVoice()
        } catch (_: Exception) {
        }
        stopListening()
        try {
            InterceptInCallService.hangup()
        } catch (_: Exception) {
        }
        if (_state.value.realCall) _state.update { it.copy(realCall = false) }
    }

    /**
     * Join the live call instead of watching it: hand the conversation to the
     * owner. Watch mode lifts first (so the mic path is allowed), then the
     * normal takeover runs — backend human mode, agent stops speaking.
     */
    fun joinCall() {
        if (!watching) return
        watching = false
        container.watchOnlySid = null
        container.watchOnlyRoom = null
        _state.update { it.copy(watchOnly = false) }
        takeover()
    }

    fun takeover() = viewModelScope.launch {
        // Stop AI listening — user is talking directly now.
        try {
            stt?.stop()
        } catch (_: Exception) {
        }
        stt = null
        try {
            container.repo.takeover(sessionId)
        } catch (_: Exception) {
        }
        socket?.sendTakeover()
        // Unmute the call stream so the USER can hear the caller,
        // then switch to speaker for hands-free takeover.
        try {
            val am = container.appContextForVoice().getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_VOICE_CALL,
                android.media.AudioManager.ADJUST_UNMUTE, 0
            )
            am.mode = android.media.AudioManager.MODE_IN_CALL
        } catch (_: Exception) {
        }
        try {
            InterceptInCallService.setSpeaker(true)
        } catch (_: Exception) {
        }
        try {
            container.stopVoice()
        } catch (_: Exception) {
        }
        _state.update { it.copy(humanMode = true, listening = false) }
    }

    fun endCall() = viewModelScope.launch {
        stopLiveKitTransport()
        stopRealCallAudio()
        // Watching is not owning: leaving the screen stops the watch, it must
        // never terminate the session the voice agent is still running.
        if (!watching) {
            try {
                container.repo.endCall(sessionId)
            } catch (_: Exception) {
            }
        }
        socket?.close()
        _state.update { it.copy(ended = true) }
    }

    override fun onCleared() {
        stopLiveKitTransport()
        stopRealCallAudio()
        socket?.close()
        // Leave watch mode clean: the next real screening must not inherit it.
        if (watching) {
            container.watchOnlySid = null
            container.watchOnlyRoom = null
        }
        super.onCleared()
    }

    companion object {
        fun provideFactory(sid: String, caller: String, c: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LiveCallViewModel(sid, caller, c) as T
            }
    }
}
