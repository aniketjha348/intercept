package com.intercept.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** DTOs mirror backend JSON 1:1 (snake_case). All defaults → tolerant to both
 *  REST turn responses and human-mode monitor responses. */

@Serializable
data class StartCallRequest(val caller: String, @SerialName("session_id") val sessionId: String? = null, val language: String = "auto", @SerialName("owner_name") val ownerName: String = "")

@Serializable
data class StartCallResponse(
    @SerialName("session_id") val sessionId: String = "",
    val event: String = "",
    val caller: String = "",
    val language: String = "auto",
)

@Serializable
data class TurnRequest(val text: String, val speaker: String = "caller", val language: String? = null)

@Serializable
data class SpeakRequest(val text: String? = null)

@Serializable
data class SpeakResponse(
    @SerialName("audio_b64") val audioB64: String? = null,
    val mime: String = "audio/wav",
    val voice: Boolean = false,
    val cached: Boolean = false,
    val language: String = "en",
)

@Serializable
data class SignalDto(
    val code: String,
    val category: String,
    val confidence: Double = 0.0,
    val evidence: String? = null,
    val weight: Int = 0,
    val origin: String = "",
)

@Serializable
data class AttackStageDto(val stage: String, val confidence: Double = 0.0, val evidence: String? = null)

@Serializable
data class TurnResponse(
    @SerialName("guardian_reply") val guardianReply: String = "",
    val risk: Int = 0,
    val level: String = "LOW",
    val language: String = "en",
    val policy: String = "CONTINUE",
    val signals: List<SignalDto> = emptyList(),
    @SerialName("attack_chain") val attackChain: List<AttackStageDto> = emptyList(),
    val why: List<String> = emptyList(),
    @SerialName("likely_objective") val likelyObjective: String = "",
    @SerialName("similar_pattern") val similarPattern: String? = null,
    @SerialName("simple_mode") val simpleMode: String = "",
    @SerialName("offer_takeover") val offerTakeover: Boolean = false,
    @SerialName("must_terminate") val mustTerminate: Boolean = false,
    @SerialName("human_mode") val humanMode: Boolean = false,
    val warning: String? = null,
)

@Serializable
data class AnalyzeTextRequest(val text: String, val channel: String = "SMS", @SerialName("source_id") val sourceId: String? = null, val language: String = "auto")

@Serializable
data class ScreenshotRequest(val text: String? = null, val image_b64: String? = null, @SerialName("source_id") val sourceId: String? = null, val language: String = "auto")

@Serializable
data class UrlRequest(val url: String, @SerialName("context_text") val contextText: String? = null, val language: String = "auto")

@Serializable
data class QrRequest(@SerialName("qr_text") val qrText: String? = null, val image_b64: String? = null, @SerialName("context_text") val contextText: String? = null, val language: String = "auto")

@Serializable
data class EventDto(
    @SerialName("event_type") val eventType: String,
    val channel: String,
    val severity: String,
    val timestamp: String = "",
    val evidence: String? = null,
    val confidence: Double = 0.0,
)

@Serializable
data class AnalyzeResponse(
    val risk: Int = 0,
    val level: String = "LOW",
    val language: String = "en",
    val policy: String = "CONTINUE",
    val signals: List<SignalDto> = emptyList(),
    @SerialName("attack_chain") val attackChain: List<AttackStageDto> = emptyList(),
    val events: List<EventDto> = emptyList(),
    val why: List<String> = emptyList(),
    @SerialName("likely_objective") val likelyObjective: String = "",
    @SerialName("similar_pattern") val similarPattern: String? = null,
    @SerialName("user_message") val userMessage: String = "",
    @SerialName("simple_mode") val simpleMode: String = "",
)

@Serializable
data class TranscriptLineDto(val speaker: String, val text: String)

@Serializable
data class TokenDto(
    val token: String = "",
    val url: String = "",
    val room: String = "",
    val identity: String = "",
)

@Serializable
data class ReportResponse(
    @SerialName("call_id") val callId: String = "",
    val caller: String = "",
    val language: String = "en",
    val risk: Int = 0,
    val level: String = "",
    @SerialName("claimed_org") val claimedOrg: String = "",
    val tactics: List<String> = emptyList(),
    val protected: List<String> = emptyList(),
    val action: String = "",
    val why: List<String> = emptyList(),
    @SerialName("likely_objective") val likelyObjective: String = "",
    val summary: String = "",
    val turns: Int = 0,
    val transcript: List<TranscriptLineDto> = emptyList(),
)

@Serializable
data class EndResponse(val event: String = "", val report: ReportResponse = ReportResponse())

@Serializable
data class LatestDto(
    @SerialName("version_code") val versionCode: Int = 1,
    @SerialName("version_name") val versionName: String = "",
    @SerialName("apk_url") val apkUrl: String = "",
    val force: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("notes_en") val notesEn: List<String> = emptyList(),
    @SerialName("notes_hi") val notesHi: List<String> = emptyList(),
)
