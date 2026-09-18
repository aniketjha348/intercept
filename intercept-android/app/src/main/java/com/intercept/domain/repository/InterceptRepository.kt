package com.intercept.domain.repository

import com.intercept.domain.model.Analysis
import com.intercept.domain.model.SecurityReport
import com.intercept.domain.model.TurnResult
import com.intercept.domain.model.UpdateInfo

/** Single front door to INTERCEPT core — every channel flows through here. */
interface InterceptRepository {
    suspend fun startCall(caller: String, owner: String = ""): String
    suspend fun sendCallerTurn(sessionId: String, text: String): TurnResult
    suspend fun takeover(sessionId: String)
    suspend fun endCall(sessionId: String): SecurityReport
    suspend fun getReport(sessionId: String): SecurityReport

    suspend fun analyzeText(text: String, channel: String): Analysis
    suspend fun analyzeScreenshot(text: String?, imageB64: String?): Analysis
    suspend fun analyzeUrl(url: String, context: String?): Analysis
    suspend fun analyzeQr(qrText: String?, imageB64: String?): Analysis

    suspend fun checkHealth(): Boolean

    /** Guardian voice bytes (WAV) for a reply, or null → use device TTS. */
    suspend fun speak(sessionId: String, text: String): ByteArray?

    /** LiveKit room credentials bound to this call session, or null. */
    suspend fun livekitToken(sessionId: String): com.intercept.domain.model.LiveKitToken?

    /** Calls being screened right now (forwarded or local). Empty = quiet. */
    suspend fun liveSessions(): List<com.intercept.domain.model.LiveSession>

    /** Send the voice agent into the call's room. False = keep current path. */
    suspend fun livekitDispatch(room: String): Boolean

    /** Forwarding target + USSD codes. Never null; not-configured is a value. */
    suspend fun forwarding(): com.intercept.domain.model.Forwarding

    /** Returns UpdateInfo when server version is newer than installed, else null. */
    suspend fun checkUpdate(installedCode: Int): UpdateInfo?
}
