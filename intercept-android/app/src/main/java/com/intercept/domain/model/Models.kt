package com.intercept.domain.model

import kotlinx.serialization.Serializable

/** §10 risk levels + §23 domain models (CallSession / RiskState / AttackEvent / SecurityReport). */

// No colour here on purpose: the ramp lives in presentation/theme/Color.kt, keyed
// to this enum. A second copy of the same hexes is a second source of truth.
enum class RiskLevel(val label: String) {
    LOW("LOW"),
    SUSPICIOUS("SUSPICIOUS"),
    HIGH("HIGH"),
    CRITICAL("CRITICAL");

    companion object {
        /**
         * Blank (an older server, or a field nobody filled) is LOW. Anything else
         * we do not recognise is SUSPICIOUS, deliberately: a level this build has
         * never heard of must not render as "safe". Fail closed, not open — a
         * newer server adding a level would otherwise paint a threat green.
         */
        fun of(raw: String): RiskLevel {
            val key = raw.trim().uppercase()
            if (key.isEmpty()) return LOW
            return values().firstOrNull { it.name == key } ?: SUSPICIOUS
        }
    }
}

data class Sig(
    val code: String,
    val category: String,
    val confidence: Double,
    val evidence: String?,
)

data class Stage(val stage: String, val confidence: Double)

data class ChatLine(val speaker: String, val text: String)

data class LiveSession(
    val sessionId: String,
    val caller: String,
    val risk: Int,
    val level: RiskLevel,
    val turns: Int,
    val objective: String = "",
    val claimedOrg: String = "",
    /** Risk climbing turn over turn — a scam escalates on purpose. */
    val escalating: Boolean = false,
    /**
     * The LiveKit room this call is actually happening in. Empty = our own
     * convention (`intercept-<session id>`, the room the app asks for). A
     * forwarded call is NOT there: the SIP dispatch rule names it after the
     * caller, so watching or joining has to use this value.
     */
    val room: String = "",
)

data class LiveKitToken(val url: String, val room: String, val token: String)

data class TurnResult(
    val reply: String,
    val risk: Int,
    val level: RiskLevel,
    val signals: List<Sig>,
    val chain: List<Stage>,
    val why: List<String>,
    val objective: String,
    val claimedOrg: String,
    val similar: String?,
    val simple: String,
    val offerTakeover: Boolean,
    val mustTerminate: Boolean,
)

data class Analysis(
    val risk: Int,
    val level: RiskLevel,
    val policy: String,
    val signals: List<Sig>,
    val chain: List<Stage>,
    val why: List<String>,
    val objective: String,
    val similar: String?,
    val userMessage: String,
    val simple: String,
)

data class SecurityReport(
    val caller: String,
    val risk: Int,
    val level: String,
    val claimedOrg: String,
    val tactics: List<String>,
    val protected: List<String>,
    val action: String,
    val why: List<String>,
    val objective: String,
    val summary: String,
    val turns: Int,
    val transcript: List<ChatLine>,
)

/**
 * One screened call, as this phone remembers it. Reports used to open on an
 * empty "Session id" box — a value an owner has no way of knowing — so the
 * history lives on the device and the report is one tap away.
 */
@Serializable
data class CallRecord(
    val sid: String,
    val caller: String = "",
    val risk: Int = 0,
    val level: String = "LOW",
    val action: String = "",
    val at: Long = 0L,
)

/**
 * How this phone hands the caller to the cloud AI: the number to forward to
 * and the USSD codes that arm and clear it. A normal app cannot speak into a
 * live cellular call, so forwarding is what lets the AI be the other party.
 */
data class Forwarding(
    val configured: Boolean = false,
    val number: String = "",
    /** "user" = this owner's own DID, "default" = shared fallback, "unset". */
    val source: String = "",
    val busyActivate: String = "",
    val busyDeactivate: String = "##67#",
    val noAnswerActivate: String = "",
    val noAnswerDeactivate: String = "##61#",
    val reason: String = "",
)

/** In-app update feed entry (mirrors backend /app/latest). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val force: Boolean,
    val notesEn: List<String>,
    val notesHi: List<String>,
)
