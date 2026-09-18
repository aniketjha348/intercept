package com.intercept.domain.repository

import com.intercept.domain.model.Analysis
import com.intercept.domain.model.SecurityReport
import com.intercept.domain.model.TurnResult
import com.intercept.domain.model.UpdateInfo

/** Single front door to INTERCEPT core — every channel flows through here. */
interface InterceptRepository {
    suspend fun startCall(caller: String): String
    suspend fun sendCallerTurn(sessionId: String, text: String): TurnResult
    suspend fun takeover(sessionId: String)
    suspend fun endCall(sessionId: String): SecurityReport
    suspend fun getReport(sessionId: String): SecurityReport

    suspend fun analyzeText(text: String, channel: String): Analysis
    suspend fun analyzeScreenshot(text: String?, imageB64: String?): Analysis
    suspend fun analyzeUrl(url: String, context: String?): Analysis
    suspend fun analyzeQr(qrText: String?, imageB64: String?): Analysis

    suspend fun checkHealth(): Boolean

    /** Returns UpdateInfo when server version is newer than installed, else null. */
    suspend fun checkUpdate(installedCode: Int): UpdateInfo?
}
