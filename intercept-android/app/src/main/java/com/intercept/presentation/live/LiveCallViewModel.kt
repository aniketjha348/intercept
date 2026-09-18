package com.intercept.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.intercept.data.DemoScript
import com.intercept.data.api.CallEvent
import com.intercept.data.api.CallWebSocket
import com.intercept.di.AppContainer
import com.intercept.service.AutoScreenService
import com.intercept.speech.CallerStt
import com.intercept.speech.LiveVoice
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
    val similar: String? = null,
    val simple: String = "",
    val guardianText: String = "",
    val offerTakeover: Boolean = false,
    val mustTerminate: Boolean = false,
    val humanMode: Boolean = false,
    val realCall: Boolean = false,
    val listening: Boolean = false,
    val voiceLive: Boolean = false,
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
    private var live: LiveVoice? = null
    /** Headless service is driving this session (talking + listening) — UI only watches. */
    private val driven: Boolean = AutoScreenService.activeCallSession == sessionId

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
                if (!driven) {
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
                    objective = r.objective, similar = r.similar, simple = r.simple,
                    guardianText = r.reply, offerTakeover = r.offerTakeover,
                    mustTerminate = r.mustTerminate, error = null,
                    ended = r.mustTerminate,
                    endedReason = if (r.mustTerminate) r.simple else it.endedReason,
                )
            }
            if (!driven) {
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
     * Production path: a real telecom call is up. Route audio to speaker,
     * point TTS at the call stream. STT starts separately via startListening()
     * (needs RECORD_AUDIO granted — the screen asks for it).
     */
    fun beginRealScreening() {
        try {
            container.audio.enter()
        } catch (_: Exception) {
        }
        try {
            container.tts.setCallMode(true)
        } catch (_: Exception) {
        }
        try {
            InterceptInCallService.setSpeaker(true)
        } catch (_: Exception) {
        }
        _state.update { it.copy(realCall = true) }
    }

    /** Caller ears on: every recognized sentence streams to the backend. */
    fun startListening() {
        if (_state.value.listening || _state.value.ended) return
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
            }
        )
    }

    fun stopListening() {
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
     * Realtime voice path (beta): mic streams to the Live bridge, guardian
     * voice streams back natively. Any failure falls back to STT+TTS turns.
     */
    fun startLiveVoice() {
        if (_state.value.voiceLive || _state.value.ended) return
        stopListening()
        val voice = try {
            LiveVoice(container.appContextForVoice(), container.backendUrl, sessionId)
        } catch (_: Exception) {
            null
        } ?: run {
            _state.update { it.copy(error = "Live voice unavailable — typed/mic turns still work.") }
            return
        }
        voice.onTranscript = { speaker, text ->
            if (text.isNotBlank()) {
                _state.update { it.copy(transcript = it.transcript + ChatLine(speaker, text)) }
            }
        }
        voice.onRisk = { score, level ->
            _state.update { it.copy(risk = score, level = RiskLevel.of(level)) }
        }
        voice.onTerminated = { reason ->
            _state.update {
                it.copy(ended = true, endedReason = reason.ifEmpty { it.endedReason })
            }
            stopRealCallAudio()
        }
        voice.onError = {
            live = null
            _state.update {
                it.copy(voiceLive = false, error = "Live voice dropped — mic/typed turns still work.")
            }
        }
        live = voice
        val ok = try {
            voice.start(container.http)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            live = null
            _state.update { it.copy(error = "Live voice unavailable — mic/typed turns still work.") }
            return
        }
        _state.update { it.copy(voiceLive = true, listening = true, error = null) }
    }

    fun stopLiveVoice() {
        try {
            live?.stop()
        } catch (_: Exception) {
        }
        live = null
        if (_state.value.voiceLive || _state.value.listening) {
            _state.update { it.copy(voiceLive = false, listening = false) }
        }
    }

    /** Hang up the telecom call + restore audio (backend session ends separately). */
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
        try {
            container.audio.exit()
        } catch (_: Exception) {
        }
        try {
            container.tts.setCallMode(false)
        } catch (_: Exception) {
        }
        if (_state.value.realCall) _state.update { it.copy(realCall = false) }
    }

    fun takeover() = viewModelScope.launch {
        try {
            container.repo.takeover(sessionId)
        } catch (_: Exception) {
        }
        socket?.sendTakeover()
        _state.update { it.copy(humanMode = true) }
    }

    fun endCall() = viewModelScope.launch {
        stopLiveVoice()
        stopRealCallAudio()
        try {
            container.repo.endCall(sessionId)
        } catch (_: Exception) {
        }
        socket?.close()
        _state.update { it.copy(ended = true) }
    }

    override fun onCleared() {
        stopLiveVoice()
        stopRealCallAudio()
        socket?.close()
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
