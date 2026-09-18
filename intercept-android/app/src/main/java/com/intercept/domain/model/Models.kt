package com.intercept.domain.model

/** §10 risk levels + §23 domain models (CallSession / RiskState / AttackEvent / SecurityReport). */

enum class RiskLevel(val label: String, val color: Long) {
    LOW("LOW", 0xFF1E7F4F),
    SUSPICIOUS("SUSPICIOUS", 0xFFA9700C),
    HIGH("HIGH", 0xFFC2410C),
    CRITICAL("CRITICAL", 0xFFC1121F);

    companion object {
        fun of(raw: String): RiskLevel =
            values().firstOrNull { it.name == raw.uppercase() } ?: LOW
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

data class TurnResult(
    val reply: String,
    val risk: Int,
    val level: RiskLevel,
    val signals: List<Sig>,
    val chain: List<Stage>,
    val why: List<String>,
    val objective: String,
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
    val turns: Int,
    val transcript: List<ChatLine>,
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
