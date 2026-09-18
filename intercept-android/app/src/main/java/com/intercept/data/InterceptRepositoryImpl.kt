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
    /** Reported the moment a session exists — mic, auto-answer and
     *  tap-to-screen all come through here, so history cannot miss a call
     *  because one call site forgot to record it. */
    private val onCallStart: (String, String) -> Unit = { _, _ -> },
    /** The final reading for that session, once the report exists. */
    private val onCallEnd: (String, Int, String, String) -> Unit = { _, _, _, _ -> },
) : InterceptRepository {

    override suspend fun startCall(caller: String, owner: String): String {
        val sid = api.startCall(StartCallRequest(caller, language = lang(), ownerName = owner)).sessionId
        if (sid.isNotBlank()) onCallStart(sid, caller)
        return sid
    }

    override suspend fun sendCallerTurn(sessionId: String, text: String): TurnResult {
        val r = api.sendTurn(sessionId, TurnRequest(text, language = lang()))
        // Human-mode monitor responses carry `warning` instead of a guardian reply.
        val reply = r.guardianReply.ifEmpty { r.warning.orEmpty() }
        return TurnResult(
            reply = reply, risk = r.risk, level = RiskLevel.of(r.level),
            signals = r.signals.map { Sig(it.code, it.category, it.confidence, it.evidence) },
            chain = r.attackChain.map { Stage(it.stage, it.confidence) },
            why = r.why, objective = r.likelyObjective, claimedOrg = r.claimedOrg,
            similar = r.similarPattern,
            simple = r.simpleMode.ifEmpty { reply },
            offerTakeover = r.offerTakeover, mustTerminate = r.mustTerminate,
        )
    }

    override suspend fun takeover(sessionId: String) {
        api.takeover(sessionId)
    }

    override suspend fun endCall(sessionId: String): SecurityReport {
        val report = api.endCall(sessionId).report.toDomain()
        onCallEnd(sessionId, report.risk, report.level, report.action)
        return report
    }

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

    override suspend fun speak(sessionId: String, text: String): ByteArray? = try {
        val r = api.speak(sessionId, com.intercept.data.api.SpeakRequest(text))
        if (!r.voice || r.audioB64.isNullOrBlank()) null
        else android.util.Base64.decode(r.audioB64, android.util.Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }

    override suspend fun livekitToken(sessionId: String): com.intercept.domain.model.LiveKitToken? = try {
        val r = api.livekitToken("app-$sessionId", "intercept-$sessionId")
        if (r.token.isBlank() || r.url.isBlank()) null
        else com.intercept.domain.model.LiveKitToken(r.url, r.room, r.token)
    } catch (_: Exception) {
        null
    }

    override suspend fun livekitDispatch(room: String): Boolean = try {
        api.livekitDispatch(mapOf("room" to room, "agent_name" to "intercept-agent"))
        true
    } catch (_: Exception) {
        false
    }

    override suspend fun liveSessions(): List<com.intercept.domain.model.LiveSession> = try {
        (api.liveSessions()["live"] ?: emptyList()).map {
            com.intercept.domain.model.LiveSession(
                it.sessionId, it.caller, it.risk,
                com.intercept.domain.model.RiskLevel.of(it.level), it.turns,
                objective = it.objective,
                claimedOrg = it.claimedOrg,
                escalating = it.escalating)
        }
    } catch (_: Exception) {
        emptyList()
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
        objective = likelyObjective, summary = summary, turns = turns,
        transcript = transcript.map { ChatLine(it.speaker, it.text) },
    )
}
