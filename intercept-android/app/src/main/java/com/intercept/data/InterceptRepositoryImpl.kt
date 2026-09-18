package com.intercept.data

import com.intercept.data.api.AnalyzeTextRequest
import com.intercept.data.api.InterceptApiService
import com.intercept.data.api.QrRequest
import com.intercept.data.api.ScreenshotRequest
import com.intercept.data.api.StartCallRequest
import com.intercept.data.api.TurnRequest
import com.intercept.data.api.UrlRequest
import com.intercept.domain.model.Analysis
import com.intercept.domain.model.ChatLine
import com.intercept.domain.model.RiskLevel
import com.intercept.domain.model.SecurityReport
import com.intercept.domain.model.Sig
import com.intercept.domain.model.Stage
import com.intercept.domain.model.TurnResult
import com.intercept.domain.model.UpdateInfo
import com.intercept.domain.repository.InterceptRepository

class InterceptRepositoryImpl(
    private val api: InterceptApiService,
    private val lang: () -> String = { "auto" },
) : InterceptRepository {

    override suspend fun startCall(caller: String): String =
        api.startCall(StartCallRequest(caller, language = lang())).sessionId

    override suspend fun sendCallerTurn(sessionId: String, text: String): TurnResult {
        val r = api.sendTurn(sessionId, TurnRequest(text, language = lang()))
        // Human-mode monitor responses carry `warning` instead of a guardian reply.
        val reply = r.guardianReply.ifEmpty { r.warning.orEmpty() }
        return TurnResult(
            reply = reply, risk = r.risk, level = RiskLevel.of(r.level),
            signals = r.signals.map { Sig(it.code, it.category, it.confidence, it.evidence) },
            chain = r.attackChain.map { Stage(it.stage, it.confidence) },
            why = r.why, objective = r.likelyObjective, similar = r.similarPattern,
            simple = r.simpleMode.ifEmpty { reply },
            offerTakeover = r.offerTakeover, mustTerminate = r.mustTerminate,
        )
    }

    override suspend fun takeover(sessionId: String) {
        api.takeover(sessionId)
    }

    override suspend fun endCall(sessionId: String): SecurityReport =
        api.endCall(sessionId).report.toDomain()

    override suspend fun getReport(sessionId: String): SecurityReport =
        api.report(sessionId).toDomain()

    override suspend fun analyzeText(text: String, channel: String): Analysis =
        api.analyzeText(AnalyzeTextRequest(text, channel, language = lang())).toDomain()

    override suspend fun analyzeScreenshot(text: String?, imageB64: String?): Analysis =
        api.analyzeScreenshot(ScreenshotRequest(text, imageB64, language = lang())).toDomain()

    override suspend fun analyzeUrl(url: String, context: String?): Analysis =
        api.analyzeUrl(UrlRequest(url, context, language = lang())).toDomain()

    override suspend fun analyzeQr(qrText: String?, imageB64: String?): Analysis =
        api.analyzeQr(QrRequest(qrText, imageB64, language = lang())).toDomain()

    override suspend fun checkHealth(): Boolean = try {
        api.health()["status"] == "ok"
    } catch (_: Exception) {
        false
    }

    override suspend fun checkUpdate(installedCode: Int): UpdateInfo? = try {
        val l = api.latest()
        if (l.apkUrl.isBlank() || l.versionCode <= installedCode) null
        else UpdateInfo(l.versionCode, l.versionName, l.apkUrl, l.force, l.notesEn, l.notesHi)
    } catch (_: Exception) {
        null
    }

    private fun com.intercept.data.api.AnalyzeResponse.toDomain() = Analysis(
        risk = risk, level = RiskLevel.of(level), policy = policy,
        signals = signals.map { Sig(it.code, it.category, it.confidence, it.evidence) },
        chain = attackChain.map { Stage(it.stage, it.confidence) },
        why = why, objective = likelyObjective, similar = similarPattern,
        userMessage = userMessage, simple = simpleMode,
    )

    private fun com.intercept.data.api.ReportResponse.toDomain() = SecurityReport(
        caller = caller, risk = risk, level = level, claimedOrg = claimedOrg,
        tactics = tactics, protected = protected, action = action, why = why,
        objective = likelyObjective, turns = turns,
        transcript = transcript.map { ChatLine(it.speaker, it.text) },
    )
}
